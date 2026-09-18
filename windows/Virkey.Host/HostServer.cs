using System.Net;
using System.Net.NetworkInformation;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;

namespace Virkey.Host;

internal sealed class HostServer : IDisposable
{
    private readonly object gate=new();
    private readonly X509Certificate2 certificate;
    private readonly PairingStore pairings;
    private readonly AuthLimiter limiter=new();
    private readonly SemaphoreSlim handshakes=new(4);
    private readonly Func<IInputSink> inputFactory;
    private readonly bool mediaEnabled;
    public AppCatalog Apps { get; }
    private CancellationTokenSource? lifetime;
    private TcpListener? listener;
    private UdpClient? discovery;
    private ClientSession? active;
    private string pin=NewPin();
    public event Action<string>? StatusChanged;
    internal event Action<string>? Diagnostic;
    public string Pin{get{lock(gate)return pin;}}
    public string Fingerprint{get;}
    public bool Running{get{lock(gate)return lifetime is not null;}}
    public string ShortFingerprint=>string.Join(" ",Enumerable.Range(0,3).Select(i=>Fingerprint.Substring(i*4,4)));
    public int Port{get;private set;}=Protocol.Port;

    public HostServer(Func<IInputSink>? factory=null,bool mediaEnabled=true,AppCatalog? apps=null,PairingStore? pairings=null)
    {
        inputFactory=factory??(()=>new WindowsInput());
        this.mediaEnabled=mediaEnabled;
        Apps=apps??(factory is null?AppCatalog.Default():new AppCatalog([]));
        this.pairings=pairings??(factory is null?PairingStore.Default():new PairingStore());
        try{certificate=this.pairings.OpenCertificate();}catch{this.pairings.Dispose();throw;}
        Fingerprint=Convert.ToHexString(SHA256.HashData(certificate.RawData));
    }
    private static string NewPin()=>RandomNumberGenerator.GetInt32(0,1000000).ToString("D6");
    public static IEnumerable<string> Addresses()=>NetworkInterface.GetAllNetworkInterfaces()
        .Where(n=>n.OperationalStatus==OperationalStatus.Up&&n.NetworkInterfaceType!=NetworkInterfaceType.Loopback)
        .SelectMany(n=>n.GetIPProperties().UnicastAddresses)
        .Where(a=>a.Address.AddressFamily==AddressFamily.InterNetwork&&!IPAddress.IsLoopback(a.Address))
        .Select(a=>a.Address.ToString()).Distinct();

    public void Start(bool loopbackOnly=false,int port=Protocol.Port)
    {
        lock(gate)
        {
            if(lifetime is not null)return;
            var cts=new CancellationTokenSource();
            try
            {
                listener=new(loopbackOnly?IPAddress.Loopback:IPAddress.Any,port);
                listener.Start(8);Port=((IPEndPoint)listener.LocalEndpoint).Port;
                if(!loopbackOnly){discovery=new(new IPEndPoint(IPAddress.Any,Port));_ = Discover(discovery,cts.Token);}
                lifetime=cts;
                _=Accept(listener,cts.Token);
            }
            catch{listener?.Stop();discovery?.Dispose();listener=null;discovery=null;cts.Dispose();throw;}
        }
        StatusChanged?.Invoke("Waiting for tablet");
    }

    private async Task Discover(UdpClient udp,CancellationToken token)
    {
        var response=JsonSerializer.SerializeToUtf8Bytes(new{protocol=1,name=Environment.MachineName,port=Port,fingerprint=Fingerprint},Protocol.Json);
        var lastResponse=DateTimeOffset.MinValue;
        try
        {
            while(!token.IsCancellationRequested)
            {
                var request=await udp.ReceiveAsync(token);
                if(request.Buffer.Length!=18||Encoding.ASCII.GetString(request.Buffer)!="VIRKEY_DISCOVER_V1")continue;
                // Limit reflection traffic while still allowing ordinary device refreshes.
                if(DateTimeOffset.UtcNow-lastResponse<TimeSpan.FromMilliseconds(100))continue;
                lastResponse=DateTimeOffset.UtcNow;
                await udp.SendAsync(response,request.RemoteEndPoint,token);
            }
        }
        catch(OperationCanceledException){}catch(ObjectDisposedException){}catch(SocketException){}
    }
    private async Task Accept(TcpListener tcp,CancellationToken token)
    {
        try
        {
            while(!token.IsCancellationRequested)
            {
                var client=await tcp.AcceptTcpClientAsync(token);
                if(!await handshakes.WaitAsync(0,token)){client.Dispose();continue;}
                _=Handle(client,token);
            }
        }
        catch(OperationCanceledException){}catch(ObjectDisposedException){}catch(SocketException){}
    }
    private async Task Handle(TcpClient client,CancellationToken serverToken)
    {
        ClientSession? session=null;
        try
        {
            using(client)
            using(var ssl=new SslStream(client.GetStream(),false))
            using(var authTimeout=CancellationTokenSource.CreateLinkedTokenSource(serverToken))
            {
                client.NoDelay=true;
                authTimeout.CancelAfter(TimeSpan.FromSeconds(6));
                await ssl.AuthenticateAsServerAsync(new SslServerAuthenticationOptions{ServerCertificate=certificate,EnabledSslProtocols=SslProtocols.Tls12|SslProtocols.Tls13,ClientCertificateRequired=false},authTimeout.Token);
                var reader=new JsonLineReader(ssl);
                using var message=await reader.Read(authTimeout.Token);
                if(message is null)return; // A first-time client only inspects the certificate.
                var root=message.RootElement;
                if(Protocol.String(root,"type",16)!="auth"||Protocol.Int(root,"protocol",1,1)!=1)throw new ProtocolException("Authenticate first.");
                if(!limiter.Allowed(DateTimeOffset.UtcNow)){await Error(ssl,"Too many pairing attempts. Wait 30 seconds.",authTimeout.Token);return;}
                var usesToken=root.TryGetProperty("token",out _);
                if(usesToken&&root.TryGetProperty("pin",out _))throw new ProtocolException("Choose one authentication method.");
                var submitted=usesToken?Protocol.String(root,"token",64):Protocol.String(root,"pin",6);
                var deviceId=usesToken?Protocol.String(root,"deviceId",32):"";
                var remember=!usesToken&&root.TryGetProperty("remember",out var rememberValue)&&rememberValue.ValueKind==JsonValueKind.True;
                var name=Protocol.String(root,"name",100);
                var authorized=false;
                lock(gate)
                {
                    authorized=usesToken?pairings.Authenticate(deviceId,submitted):Protocol.PinMatches(pin,submitted);
                    if(!authorized){limiter.Failed(DateTimeOffset.UtcNow);}
                    else if(active is null&&!serverToken.IsCancellationRequested)
                    {
                        var credential=remember?pairings.Issue():null;
                        session=new(ssl,reader,new InputSession(inputFactory()),serverToken,mediaEnabled,Apps,credential);
                        active=session;
                    }
                }
                if(session is null)
                {
                    await Error(ssl,authorized?"Another tablet is connected.":usesToken?"Saved pairing was revoked. Pair this PC again.":"Incorrect pairing PIN.",
                        authTimeout.Token,authorized?"busy":usesToken?"pairingRequired":"invalidPin");
                    return;
                }
                authTimeout.CancelAfter(Timeout.InfiniteTimeSpan);
                StatusChanged?.Invoke($"Connected: {name}");
                await session.Run();
            }
        }
        catch(OperationCanceledException){}catch(IOException){}catch(AuthenticationException ex){Diagnostic?.Invoke(ex.ToString());}catch(SocketException){}catch(ProtocolException){}
        catch(Exception ex){StatusChanged?.Invoke($"Connection ended: {ex.Message}");}
        finally
        {
            session?.Dispose();
            lock(gate){if(ReferenceEquals(active,session))active=null;}
            if(session is not null&&Running)StatusChanged?.Invoke("Waiting for tablet");
            handshakes.Release();
        }
    }
    private static async Task Error(SslStream stream,string message,CancellationToken token,string code="")
    {
        var bytes=Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new{type="error",message,code},Protocol.Json)+"\n");
        await stream.WriteAsync(bytes,token);
    }
    public void ResetPairing()
    {
        lock(gate){pairings.RevokeAll();pin=NewPin();active?.Dispose();}
        StatusChanged?.Invoke(Running?"Pairing reset. Waiting for tablet":"Stopped");
    }
    public void Stop()
    {
        lock(gate)
        {
            lifetime?.Cancel();listener?.Stop();discovery?.Dispose();active?.Dispose();
            listener=null;discovery=null;lifetime?.Dispose();lifetime=null;
        }
        StatusChanged?.Invoke("Stopped");
    }
    public void Dispose(){Stop();certificate.Dispose();pairings.Dispose();}
}

internal sealed class ClientSession:IDisposable
{
    private readonly SslStream stream;
    private readonly JsonLineReader reader;
    private readonly InputSession input;
    private readonly CancellationTokenSource lifetime;
    private readonly Channel<object> outgoing=Channel.CreateBounded<object>(new BoundedChannelOptions(16){SingleReader=true,FullMode=BoundedChannelFullMode.Wait});
    private readonly Channel<JsonElement> commands=Channel.CreateBounded<JsonElement>(new BoundedChannelOptions(8){SingleReader=true,SingleWriter=true,FullMode=BoundedChannelFullMode.Wait});
    private readonly MediaBridge media=new();
    private readonly bool mediaEnabled;
    private readonly AppCatalog apps;
    private readonly PairingCredential? credential;
    private readonly Channel<JsonElement> appCommands=Channel.CreateBounded<JsonElement>(new BoundedChannelOptions(4){SingleReader=true,SingleWriter=true,FullMode=BoundedChannelFullMode.Wait});
    private long heartbeat=Environment.TickCount64;
    private int disposed;
    public ClientSession(SslStream stream,JsonLineReader reader,InputSession input,CancellationToken token,bool mediaEnabled,AppCatalog apps,PairingCredential? credential=null)
    {this.stream=stream;this.reader=reader;this.input=input;this.mediaEnabled=mediaEnabled;this.apps=apps;this.credential=credential;lifetime=CancellationTokenSource.CreateLinkedTokenSource(token);}
    private void Send(object message)
    {
        if(!outgoing.Writer.TryWrite(message)){Dispose();throw new ProtocolException("Tablet is not reading messages.");}
    }
    public async Task Run()
    {
        var leds=WindowsInput.ReadLeds();
        Send(new{type="ready",name=Environment.MachineName,ledsKnown=false,capsLock=leds.Caps,numLock=leds.Num,dock=true,pcId=apps.PcId,
            rememberSupported=true,deviceId=credential?.DeviceId,token=credential?.Token});
        var tasks=new[]{Write(),Watchdog(),PollMedia(),MediaCommands(),AppCommands(),Receive()};
        try{await Task.WhenAny(tasks);}
        finally
        {
            Dispose();
            try{await Task.WhenAll(tasks);}catch(OperationCanceledException){}catch(IOException){}catch(ObjectDisposedException){}catch(ProtocolException){}
        }
    }
    private async Task Write()
    {
        await foreach(var message in outgoing.Reader.ReadAllAsync(lifetime.Token))
        {
            var bytes=Encoding.UTF8.GetBytes(JsonSerializer.Serialize(message,Protocol.Json)+"\n");
            using var timeout=CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token);timeout.CancelAfter(TimeSpan.FromSeconds(3));
            await stream.WriteAsync(bytes,timeout.Token);
        }
    }
    private async Task Watchdog()
    {
        while(!lifetime.IsCancellationRequested)
        {
            await Task.Delay(250,lifetime.Token);
            if(Environment.TickCount64-Interlocked.Read(ref heartbeat)>4000){Dispose();return;}
        }
    }
    private async Task PollMedia()
    {
        while(!lifetime.IsCancellationRequested)
        {
            Send(mediaEnabled?await media.Read(lifetime.Token):new MediaSnapshot());
            Send(WindowsInput.Leds());
            await Task.Delay(1000,lifetime.Token);
        }
    }
    private async Task MediaCommands()
    {
        await foreach(var command in commands.Reader.ReadAllAsync(lifetime.Token))
        {
            var error=await media.Command(command,lifetime.Token);
            if(error is not null)Send(new{type="commandError",message=error});
        }
    }
    private async Task Receive()
    {
        while(!lifetime.IsCancellationRequested)
        {
            using var message=await reader.Read(lifetime.Token);
            if(message is null)return;
            var root=message.RootElement;
            switch(Protocol.String(root,"type",24))
            {
                case "ping":Interlocked.Exchange(ref heartbeat,Environment.TickCount64);Send(new{type="pong"});break;
                case "release":input.Release();break;
                case "key":input.Key(Protocol.Int(root,"usage",0,255),Protocol.Bool(root,"down"));break;
                case "mediaKey":input.Media(Protocol.Int(root,"usage",0,65535),Protocol.Bool(root,"down"));break;
                case "button":input.Button(Protocol.Int(root,"button",1,7),Protocol.Bool(root,"down"));break;
                case "move":input.Move(Protocol.Int(root,"dx",-32767,32767),Protocol.Int(root,"dy",-32767,32767));break;
                case "scroll":input.Scroll(Protocol.Int(root,"amount",-120,120));break;
                case "media":if(!commands.Writer.TryWrite(root.Clone()))throw new ProtocolException("Too many media commands.");break;
                case "apps": case "launchApp":
                    if(!appCommands.Writer.TryWrite(root.Clone()))throw new ProtocolException("Too many app requests.");break;
                default:throw new ProtocolException("Unsupported message.");
            }
        }
    }
    private async Task AppCommands()
    {
        await foreach(var command in appCommands.Reader.ReadAllAsync(lifetime.Token))
        {
            try
            {
                if(Protocol.String(command,"type")=="apps")
                {
                    Send(new{type="appsBegin",pcId=apps.PcId});
                    var entries=await Task.Run(()=>apps.List(lifetime.Token),lifetime.Token).WaitAsync(TimeSpan.FromSeconds(30),lifetime.Token);
                    foreach(var entry in entries)
                    {
                        // Leave queue capacity for heartbeat/media frames during a large catalog.
                        while(outgoing.Reader.Count>=8)await Task.Delay(15,lifetime.Token);
                        await outgoing.Writer.WriteAsync(new{type="app",pcId=apps.PcId,id=entry.Id,name=entry.Name,icon=entry.Icon},lifetime.Token);
                    }
                    await outgoing.Writer.WriteAsync(new{type="appsEnd",pcId=apps.PcId},lifetime.Token);
                }
                else
                {
                    await Task.Run(()=>apps.Launch(Protocol.String(command,"pcId",80),Protocol.String(command,"id",80),lifetime.Token),lifetime.Token);
                    Send(new{type="appLaunched",id=Protocol.String(command,"id",80)});
                }
            }
            catch(OperationCanceledException)when(lifetime.IsCancellationRequested){throw;}
            catch(Exception error){Send(new{type="appError",message=error.Message[..Math.Min(error.Message.Length,200)]});}
        }
    }
    public void Dispose()
    {
        if(Interlocked.Exchange(ref disposed,1)!=0)return;
        input.Dispose();lifetime.Cancel();outgoing.Writer.TryComplete();commands.Writer.TryComplete();appCommands.Writer.TryComplete();
        try{stream.Close();}catch{}
    }
}
