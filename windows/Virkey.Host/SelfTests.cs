using System.Collections.Concurrent;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Virkey.Host;

internal static class SelfTests
{
    private static void Check(bool condition,string message){if(!condition)throw new InvalidOperationException(message);}
    private static async Task Until(Func<bool> condition,int timeout=2000)
    {
        var started=Environment.TickCount64;
        while(!condition())
        {
            if(Environment.TickCount64-started>timeout)throw new TimeoutException("Expected state was not reached.");
            await Task.Delay(10);
        }
    }
    private static async Task Reject(Func<Task> action)
    {
        try{await action();}catch(ProtocolException){return;}
        throw new InvalidOperationException("Invalid protocol data was accepted.");
    }
    public static async Task Run(Action<string> report)
    {
        TestBranding();report("PASS nine embedded and native EXE icon resolutions, plus owned window icons");
        TestMappingAndRelease();report("PASS HID mapping, duplicate suppression, combined button masks, release and disposal");
        await TestRepeat();report("PASS rapid re-press cannot revive an old keyboard repeat task");
        await TestProtocol();report("PASS JSON framing, length/depth/type limits, truncated messages and PIN rate limit");
        await TestConnections(report);report("PASS TLS pin rejection, PIN rejection, authenticated input, second-client isolation, explicit release, disconnect, PIN reset and stop");
        await TestHeartbeat();report("PASS four-second heartbeat expiry releases held keys and buttons");
        await TestRateLimit();report("PASS pairing attempts globally rate limited over real TLS connections");
        await TestAppCatalog();report("PASS app catalog persistence, known-ID launch, wrong-PC rejection and authenticated app commands");
        TestArtwork();report("PASS enlarged artwork dimensions, aspect ratio, byte cap and no upscaling");
        TestSavedPairing();report("PASS DPAPI pairing persistence, stable certificate, hashed tokens, exclusive access, revocation and corruption rejection");
        await TestRememberedConnections();report("PASS first-pair token issuance, PIN-free reconnect after restart, bad-token rejection, busy-client isolation and reset revocation");
        report("Tests used recording input only. No Windows input or media commands injected.");
    }
    private static void TestBranding()
    {
        int[] sizes=[16,20,24,32,40,48,64,128,256];
        using var stream=typeof(Branding).Assembly.GetManifestResourceStream(Branding.IconResource)
            ??throw new InvalidOperationException("Missing embedded application icon.");
        using var reader=new BinaryReader(stream);
        Check(reader.ReadUInt16()==0&&reader.ReadUInt16()==1&&reader.ReadUInt16()==sizes.Length,"Invalid multi-resolution icon header.");
        foreach(var size in sizes)
        {
            var width=reader.ReadByte();var height=reader.ReadByte();
            Check((width==0?256:width)==size&&(height==0?256:height)==size,"Missing icon size.");
            reader.ReadBytes(2);
            Check(reader.ReadUInt16()==1&&reader.ReadUInt16()==32,"Expected a 32-bit icon.");
            var length=reader.ReadUInt32();var offset=reader.ReadUInt32();
            Check(length>0&&offset>=6+16*sizes.Length&&offset+length<=stream.Length,"Invalid icon frame bounds.");
            var nextEntry=stream.Position;
            stream.Position=offset;
            using var frame=new MemoryStream(reader.ReadBytes((int)length));
            using var bitmap=new Bitmap(frame);
            stream.Position=nextEntry;
            Check(bitmap.Width==size&&bitmap.Height==size,$"Icon frame requested {size}px but decoded {bitmap.Width}x{bitmap.Height}.");
            var colors=new HashSet<int>();
            for(var y=0;y<size;y++)for(var x=0;x<size;x++)colors.Add(bitmap.GetPixel(x,y).ToArgb());
            Check(colors.Count>2,"Icon is blank or failed to render.");
            var extracted=PrivateExtractIcons(Environment.ProcessPath!,0,size,size,out var nativeIcon,out _,1,0);
            try
            {
                Check(extracted==1&&nativeIcon!=IntPtr.Zero,"Could not extract the native EXE icon.");
                using var shellIcon=Icon.FromHandle(nativeIcon);
                using var shellBitmap=shellIcon.ToBitmap();
                Check(shellBitmap.Size==bitmap.Size,"Native EXE icon has an unexpected size.");
                for(var y=0;y<size;y++)for(var x=0;x<size;x++)
                    Check(shellBitmap.GetPixel(x,y).ToArgb()==bitmap.GetPixel(x,y).ToArgb(),"Native EXE icon differs from the Virkey logo.");
            }
            finally{if(nativeIcon!=IntPtr.Zero)DestroyIcon(nativeIcon);}
            // The .NET 8 icon selector treats the 256px directory byte as zero.
            // Windows Explorer uses that PNG directly; forms use smaller sizes.
            if(size<256)
            {
                using var icon=Branding.LoadIcon(size);
                using var rendered=icon.ToBitmap();
                Check(rendered.Size==bitmap.Size,"Window icon did not preserve its requested size.");
            }
        }
    }
    [DllImport("user32.dll",CharSet=CharSet.Unicode)]
    private static extern uint PrivateExtractIcons(string fileName,int index,int width,int height,out IntPtr icon,out uint iconId,uint count,uint flags);
    [DllImport("user32.dll")]
    private static extern bool DestroyIcon(IntPtr icon);
    private static void TestMappingAndRelease()
    {
        for(var usage=4;usage<=0x65;usage++)Check(InputMap.TryKey(usage,out _),$"Missing keyboard usage {usage:x}.");
        for(var usage=0xe0;usage<=0xe7;usage++)Check(InputMap.TryKey(usage,out _),"Missing modifier.");
        Check(InputMap.TryKey(0x28,out var enter)&&InputMap.TryKey(0x58,out var numEnter)&&!enter.Extended&&numEnter.Extended,"Enter and keypad Enter must differ.");
        Check(!InputMap.TryKey(0xff,out _),"Unknown keyboard usage mapped.");
        var sink=new RecordingInput();
        var input=new InputSession(sink,repeat:false);
        input.Key(4,true);input.Key(4,true);input.Key(0xe0,true);
        input.Button(7,true);input.Button(1,true);input.Media(0xcd,true);input.Media(0xcd,true);
        Check(sink.Events.Count==6,"Duplicate downs or combined mouse mask mishandled.");
        input.Move(10,-8);input.Scroll(-1);
        input.Release();input.Release();
        Check(sink.Events.Count==14,"Release must emit exactly one up for each held input.");
        Check(sink.Events.Count(e=>e=="B1:False")==1&&sink.Events.Count(e=>e=="B2:False")==1&&sink.Events.Count(e=>e=="B4:False")==1,"Combined buttons were not fully released.");
        try{input.Key(0xff,true);throw new InvalidOperationException("Invalid usage accepted.");}catch(ProtocolException){}
        input.Dispose();var count=sink.Events.Count;
        input.Key(4,true);input.Button(1,true);input.Move(1,1);
        Check(sink.Events.Count==count,"Input after disposal escaped.");
    }
    private static void TestArtwork()
    {
        using var large=new Bitmap(1200,800);
        using(var graphics=Graphics.FromImage(large))graphics.Clear(Color.CadetBlue);
        var encoded=MediaBridge.EncodeArtwork(large);
        Check(encoded is {Length:>0 and <=262144},"Artwork exceeded the protocol byte cap.");
        using var stream=new MemoryStream(encoded!);
        using var decoded=Image.FromStream(stream);
        Check(decoded.Width==640&&decoded.Height==426,"Enlarged artwork lost its resolution or aspect ratio.");
        using var small=new Bitmap(120,120);
        using var smallStream=new MemoryStream(MediaBridge.EncodeArtwork(small)!);
        using var smallDecoded=Image.FromStream(smallStream);
        Check(smallDecoded.Width==120&&smallDecoded.Height==120,"Small artwork should not be upscaled.");
        using var oversized=new Bitmap(8193,1);
        Check(MediaBridge.EncodeArtwork(oversized) is null,"Oversized source artwork accepted.");
    }
    private static void TestSavedPairing()
    {
        var directory=Directory.CreateTempSubdirectory("virkey-pairing-test-");
        var path=Path.Combine(directory.FullName,"pairing.dat");
        try
        {
            PairingCredential first;string fingerprint;
            using(var saved=new PairingStore(path))
            {
                using var cert=saved.OpenCertificate();fingerprint=Convert.ToHexString(SHA256.HashData(cert.RawData));
                first=saved.Issue();Check(saved.Authenticate(first.DeviceId,first.Token),"New credential rejected.");
                Check(!saved.Authenticate(first.DeviceId,new string('0',64)),"Incorrect token accepted.");
                Check(!Encoding.UTF8.GetString(File.ReadAllBytes(path)).Contains(first.Token),"Token stored in clear text.");
                try{using var second=new PairingStore(path);throw new InvalidOperationException("Two writers accepted.");}catch(IOException){}
            }
            using(var saved=new PairingStore(path))
            {
                using var cert=saved.OpenCertificate();Check(Convert.ToHexString(SHA256.HashData(cert.RawData))==fingerprint,"Host identity changed on restart.");
                Check(saved.Authenticate(first.DeviceId,first.Token),"Persisted credential rejected.");
                for(var index=0;index<16;index++)saved.Issue();
                Check(saved.Count==16&&!saved.Authenticate(first.DeviceId,first.Token),"Trusted-device bound failed.");
                saved.RevokeAll();Check(saved.Count==0,"Revocation failed.");
            }
            using(var saved=new PairingStore(path))Check(saved.Count==0&&!saved.Authenticate(first.DeviceId,first.Token),"Revocation did not survive restart.");
            var corrupt=File.ReadAllBytes(path);corrupt[^1]^=1;File.WriteAllBytes(path,corrupt);
            try{using var saved=new PairingStore(path);throw new InvalidOperationException("Corrupt trust store accepted.");}catch(CryptographicException){}
            Check(File.ReadAllBytes(path).SequenceEqual(corrupt),"Corrupt identity was silently replaced.");
        }
        finally{foreach(var suffix in new[]{"",".tmp",".lock"})File.Delete(path+suffix);directory.Delete();}
    }
    private static async Task TestRememberedConnections()
    {
        var directory=Directory.CreateTempSubdirectory("virkey-reconnect-test-");
        var path=Path.Combine(directory.FullName,"pairing.dat");
        try
        {
            string deviceId,token,fingerprint;
            using(var host=new HostServer(()=>new RecordingInput(),mediaEnabled:false,pairings:new PairingStore(path)))
            {
                host.Start(loopbackOnly:true,port:0);fingerprint=host.Fingerprint;
                using var client=await TestClient.Open(host);
                await client.Send(new{type="auth",protocol=1,pin=host.Pin,remember=true,name="Pairing test"});
                using var ready=await client.Type("ready");
                deviceId=ready.RootElement.GetProperty("deviceId").GetString()!;token=ready.RootElement.GetProperty("token").GetString()!;
                Check(deviceId.Length==32&&token.Length==64,"Missing reconnect credential.");
            }
            var sink=new RecordingInput();
            using(var host=new HostServer(()=>sink,mediaEnabled:false,pairings:new PairingStore(path)))
            {
                Check(host.Fingerprint==fingerprint,"Restart changed trusted certificate.");host.Start(loopbackOnly:true,port:0);
                using(var bad=await TestClient.Open(host))
                {
                    await bad.Send(new{type="auth",protocol=1,deviceId,token=new string('0',64),name="Bad token"});
                    using var error=await bad.Type("error");Check(error.RootElement.GetProperty("code").GetString()=="pairingRequired","Bad token did not require re-pairing.");
                    Check(sink.Events.Count==0,"Unauthorized input was created.");
                }
                using var client=await TestClient.Open(host,fingerprint);
                await client.Send(new{type="auth",protocol=1,deviceId,token,name="Remembered tablet"});
                using var ready=await client.Type("ready");
                Check(ready.RootElement.GetProperty("token").ValueKind==JsonValueKind.Null,"Reconnect should not rotate the token before client acknowledgement.");
                await client.Send(new{type="key",usage=4,down=true});await client.Barrier();
                using(var second=await TestClient.Open(host))
                {
                    await second.Send(new{type="auth",protocol=1,deviceId,token,name="Second client"});
                    using var error=await second.Type("error");Check(error.RootElement.GetProperty("code").GetString()=="busy","Second client bypassed isolation.");
                }
                Check(!sink.Events.Any(e=>e.StartsWith("K")&&e.EndsWith("False")),"Rejected client released the first client's keys.");
                host.ResetPairing();await Until(()=>sink.Events.Count>=2);
                using var revoked=await TestClient.Open(host);
                await revoked.Send(new{type="auth",protocol=1,deviceId,token,name="Revoked tablet"});
                using var rejection=await revoked.Type("error");
                Check(rejection.RootElement.GetProperty("code").GetString()=="pairingRequired","Reset did not revoke the reconnect token.");
            }
        }
        finally{foreach(var suffix in new[]{"",".tmp",".lock"})File.Delete(path+suffix);directory.Delete();}
    }
    private static async Task TestRepeat()
    {
        var delays=new ConcurrentQueue<TaskCompletionSource>();
        Task Delay(int _) {var next=new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);delays.Enqueue(next);return next.Task;}
        var sink=new RecordingInput();using var input=new InputSession(sink,repeatDelay:Delay);
        input.Key(4,true);Check(delays.TryDequeue(out var oldDelay),"Repeat not scheduled.");
        input.Key(4,false);input.Key(4,true);Check(delays.TryDequeue(out var newDelay),"New repeat not scheduled.");
        oldDelay!.SetResult();await Task.Delay(60);
        Check(sink.Events.Count==3,"Old repeat resumed after re-press.");
        newDelay!.SetResult();await Until(()=>sink.Events.Count==4);
        input.Release();
        while(delays.TryDequeue(out var pending))pending.TrySetResult();
        await Task.Delay(30);Check(sink.Events.Count==5,"Repeat survived release.");
    }
    private static async Task TestProtocol()
    {
        using var stream=new MemoryStream(Encoding.UTF8.GetBytes("{\"type\":\"ping\"}\n{\"type\":\"release\"}\n"));
        var reader=new JsonLineReader(stream);
        using var first=await reader.Read(default);using var second=await reader.Read(default);
        Check(first!.RootElement.GetProperty("type").GetString()=="ping"&&second!.RootElement.GetProperty("type").GetString()=="release","Buffered messages lost ordering.");
        foreach(var invalid in new[]{"[]\n","{invalid}\n","{}",new string(' ',524289)+"\n",new string('[',17)+new string(']',17)+"\n"})
        {
            using var malformed=new MemoryStream(Encoding.UTF8.GetBytes(invalid));
            await Reject(async()=>{using var ignored=await new JsonLineReader(malformed).Read(default);});
        }
        using var badNumber=JsonDocument.Parse("{\"usage\":\"4\"}");
        await Reject(()=>{Protocol.Int(badNumber.RootElement,"usage",0,255);return Task.CompletedTask;});
        Check(Protocol.PinMatches("123456","123456")&&!Protocol.PinMatches("123456","12345"),"PIN comparison failed.");
        var limiter=new AuthLimiter();var now=DateTimeOffset.UtcNow;
        for(var attempt=0;attempt<5;attempt++)limiter.Failed(now);
        Check(!limiter.Allowed(now)&&limiter.Allowed(now.AddSeconds(31)),"Pairing rate limiter failed.");
    }
    private static async Task TestConnections(Action<string> report)
    {
        var sink=new RecordingInput();using var host=new HostServer(()=>sink,mediaEnabled:false);host.Start(loopbackOnly:true,port:0);
        host.Diagnostic+=report;
        try{using var rejected=await TestClient.Open(host,"00");throw new InvalidOperationException("Changed certificate accepted.");}catch(AuthenticationException){}catch(IOException){}
        using(var rejected=await TestClient.Open(host))
        {
            await rejected.Send(new{type="auth",protocol=1,pin=host.Pin=="000000"?"111111":"000000",name="Test"});
            using var error=await rejected.Type("error");Check(sink.Events.Count==0,"Unauthenticated input injected.");
        }
        using(var owner=await TestClient.Authenticate(host))
        {
            await owner.Send(new{type="key",usage=0xe1,down=true});await owner.Barrier();
            Check(sink.Events.Count==1,"Authorized key missing.");
            using(var intruder=await TestClient.Open(host))
            {
                await intruder.Send(new{type="auth",protocol=1,pin=host.Pin,name="Other tablet"});
                using var error=await intruder.Type("error");
            }
            await owner.Barrier();Check(sink.Events.Count==1,"Rejected connection released the owner's held key.");
            await owner.Send(new{type="button",button=7,down=true});await owner.Barrier();
            await owner.Send(new{type="release"});await owner.Barrier();
            Check(sink.Events.Count==8,"Explicit release did not release every held input.");
            await owner.Send(new{type="key",usage=0xe0,down=true});await owner.Barrier();
        }
        await Until(()=>sink.Events.Count==10);
        using(var resetClient=await TestClient.AuthenticateEventually(host))
        {
            await resetClient.Send(new{type="button",button=1,down=true});await resetClient.Barrier();
            host.ResetPairing();await Until(()=>sink.Events.Count==12);
        }
        using(var stoppedClient=await TestClient.AuthenticateEventually(host))
        {
            await stoppedClient.Send(new{type="button",button=4,down=true});await stoppedClient.Barrier();
            host.Stop();await Until(()=>sink.Events.Count==14);
        }
        Check(!host.Running,"Stop left the listener running.");
    }
    private static async Task TestHeartbeat()
    {
        var sink=new RecordingInput();using var host=new HostServer(()=>sink,mediaEnabled:false);host.Start(loopbackOnly:true,port:0);
        using var client=await TestClient.Authenticate(host);
        await client.Send(new{type="key",usage=0xe1,down=true});
        await client.Send(new{type="button",button=1,down=true});await client.Barrier();
        var started=Environment.TickCount64;
        await Until(()=>sink.Events.Count==4,6000);
        Check(Environment.TickCount64-started>=3500,"Session ended before its heartbeat deadline.");
        Check(sink.Events.Contains("B1:False"),"Heartbeat loss left mouse held.");
    }
    private static async Task TestRateLimit()
    {
        var sink=new RecordingInput();using var host=new HostServer(()=>sink,mediaEnabled:false);host.Start(loopbackOnly:true,port:0);
        for(var attempt=0;attempt<5;attempt++)
        {
            using var rejected=await TestClient.Open(host);
            await rejected.Send(new{type="auth",protocol=1,pin=host.Pin=="000000"?"111111":"000000",name="Test"});
            using var error=await rejected.Type("error");
        }
        using var blocked=await TestClient.Open(host);
        await blocked.Send(new{type="auth",protocol=1,pin=host.Pin,name="Test"});
        using var reply=await blocked.Type("error");
        Check(reply.RootElement.GetProperty("message").GetString()!.Contains("Too many"),"Pairing limiter bypassed across clients.");
        Check(sink.Events.Count==0,"Authentication tests injected input.");
    }
    private static async Task TestAppCatalog()
    {
        var folder=Path.Combine(Path.GetTempPath(),"virkey-app-test-"+Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(folder);
        try
        {
            var path=Path.Combine(folder,"Sample.exe");
            File.WriteAllText(path,"Test fixture: never executed");
            var config=Path.Combine(folder,"apps.json");
            var launched=new List<string>();
            var catalog=new AppCatalog([],config,launched.Add);
            catalog.Add(path);
            var reopened=new AppCatalog([],config,launched.Add);
            Check(reopened.PcId==catalog.PcId&&reopened.ManualPaths.SequenceEqual([path]),"Saved app settings lost PC identity or paths.");
            var entries=catalog.List(default,icons:false);
            Check(entries.Count==1&&entries[0].Name=="Sample","Manual app missing from catalog.");
            var id=entries[0].Id;
            try{catalog.Launch("another-pc",id,default);throw new Exception("Wrong PC was accepted.");}catch(InvalidOperationException){}
            try{catalog.Launch(catalog.PcId,path,default);throw new Exception("Raw launch path was accepted.");}catch(InvalidOperationException){}
            Check(launched.Count==0,"Rejected app command launched a process.");
            catalog.Launch(catalog.PcId,id,default);
            Check(launched.SequenceEqual([path]),"Known app did not reach the recording launcher.");
            Check(!AppCatalog.IsLocalTarget(@"\\server\share\app.exe")&&!AppCatalog.IsLocalTarget("cmd /c echo hi")&&!AppCatalog.IsLocalTarget(Path.Combine(folder,"run.ps1")),"Arbitrary target allowed.");

            using var host=new HostServer(()=>new RecordingInput(),mediaEnabled:false,apps:catalog);
            host.Start(loopbackOnly:true,port:0);
            using var client=await TestClient.Authenticate(host);
            await client.Send(new{type="apps"});
            using var entry=await client.Type("app");
            Check(entry.RootElement.GetProperty("id").GetString()==id,"Catalog wire ID changed.");
            using var completed=await client.Type("appsEnd");
            await client.Send(new{type="launchApp",pcId=catalog.PcId,id});
            using var opened=await client.Type("appLaunched");
            Check(launched.Count==2,"Authenticated app was not dispatched.");
            await client.Send(new{type="launchApp",pcId="wrong",id});
            using var rejected=await client.Type("appError");
            Check(launched.Count==2,"Mismatched PC ID launched an app.");
            catalog.Remove(path);
            Check(catalog.List(default,icons:false).Count==0,"Removed manual app remained in catalog.");
            Check(new AppCatalog([],config,launched.Add).ManualPaths.Length==0,"App removal did not persist.");
        }
        finally { Directory.Delete(folder,true); }
    }

    private sealed class RecordingInput:IInputSink
    {
        public ConcurrentQueue<string> Events{get;}=new();
        public void Key(KeyCode code,bool down)=>Events.Enqueue($"K{code.Scan}/{code.Extended}/{code.VirtualKey}:{down}");
        public void Button(int button,bool down)=>Events.Enqueue($"B{button}:{down}");
        public void Move(int dx,int dy)=>Events.Enqueue($"M{dx}/{dy}");
        public void Scroll(int amount)=>Events.Enqueue($"S{amount}");
    }
    private sealed class TestClient:IDisposable
    {
        private readonly TcpClient tcp;
        private readonly SslStream ssl;
        private readonly JsonLineReader reader;
        private TestClient(TcpClient tcp,SslStream ssl){this.tcp=tcp;this.ssl=ssl;reader=new(ssl);}
        public static async Task<TestClient> Open(HostServer host,string? fingerprint=null)
        {
            var tcp=new TcpClient();SslStream? ssl=null;
            try
            {
                using var timeout=new CancellationTokenSource(TimeSpan.FromSeconds(5));
                await tcp.ConnectAsync(IPAddress.Loopback,host.Port,timeout.Token);
                ssl=new(tcp.GetStream(),false,(_,certificate,_,_)=>certificate is not null&&Convert.ToHexString(SHA256.HashData(certificate.GetRawCertData()))==(fingerprint??host.Fingerprint));
                await ssl.AuthenticateAsClientAsync(new SslClientAuthenticationOptions{TargetHost="Virkey Host",EnabledSslProtocols=SslProtocols.Tls12|SslProtocols.Tls13},timeout.Token);
                return new(tcp,ssl);
            }
            catch{ssl?.Dispose();tcp.Dispose();throw;}
        }
        public static async Task<TestClient> Authenticate(HostServer host)
        {
            var client=await Open(host);
            try
            {
                await client.Send(new{type="auth",protocol=1,pin=host.Pin,name="Self-test tablet"});
                using var ready=await client.Type("ready");
                Check(!ready.RootElement.GetProperty("ledsKnown").GetBoolean(),"Unreliable LED state must be marked unknown.");
                return client;
            }
            catch{client.Dispose();throw;}
        }
        public static async Task<TestClient> AuthenticateEventually(HostServer host)
        {
            for(var attempt=0;attempt<20;attempt++)
            {
                try{return await Authenticate(host);}catch(ProtocolException)when(attempt<19){await Task.Delay(25);}
            }
            throw new InvalidOperationException("Could not authenticate after previous client closed.");
        }
        public async Task Send(object value)
        {
            using var timeout=new CancellationTokenSource(TimeSpan.FromSeconds(5));
            await ssl.WriteAsync(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(value,Protocol.Json)+"\n"),timeout.Token);
        }
        public async Task<JsonDocument> Type(string type)
        {
            using var timeout=new CancellationTokenSource(TimeSpan.FromSeconds(5));
            while(true)
            {
                var message=await reader.Read(timeout.Token)??throw new ProtocolException("Host closed the connection.");
                var actual=message.RootElement.GetProperty("type").GetString();
                if(actual==type)return message;
                if(actual=="error"){var reason=message.RootElement.GetProperty("message").GetString();message.Dispose();throw new ProtocolException(reason??"Host rejected request.");}
                message.Dispose();
            }
        }
        public async Task Barrier(){await Send(new{type="ping"});using var pong=await Type("pong");}
        public void Dispose(){ssl.Dispose();tcp.Dispose();}
    }
}
