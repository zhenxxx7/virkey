using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Windows.Media;
using Windows.Media.Control;
using Windows.Storage.Streams;

namespace Virkey.Host;

internal sealed record MediaSnapshot
{
    public string Type {get;init;}="nowPlaying";
    public bool Available {get;init;}
    public string SessionId {get;init;}="";
    public string TrackId {get;init;}="";
    public string Title {get;init;}="";
    public string Artist {get;init;}="";
    public string Album {get;init;}="";
    public string Player {get;init;}="";
    public bool Playing {get;init;}
    public long PositionMs {get;init;}
    public long DurationMs {get;init;}
    public bool CanPlay {get;init;}
    public bool CanPause {get;init;}
    public bool CanPrevious {get;init;}
    public bool CanNext {get;init;}
    public bool CanStop {get;init;}
    public bool CanSeek {get;init;}
    public bool CanShuffle {get;init;}
    public bool CanRepeat {get;init;}
    public bool Shuffle {get;init;}
    public string Repeat {get;init;}="off";
    public string ArtworkId {get;init;}="";
    [System.Text.Json.Serialization.JsonIgnore(Condition=System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull)]
    public string? Artwork {get;init;}
}

internal sealed class MediaBridge
{
    private readonly SemaphoreSlim gate=new(1,1);
    private GlobalSystemMediaTransportControlsSessionManager? manager;
    private string? previousArtwork;
    private string? previousTrack;

    private async Task<GlobalSystemMediaTransportControlsSession?> Session(CancellationToken token)
    {
        manager??=await GlobalSystemMediaTransportControlsSessionManager.RequestAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(2),token);
        return manager.GetCurrentSession();
    }
    private static string Limit(string? text)=>text is null?"":text[..Math.Min(text.Length,2048)];
    private static string Hash(string value)=>Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value)))[..24];
    private static string Track(string sessionId,GlobalSystemMediaTransportControlsSessionMediaProperties props)=>Hash(sessionId+"\n"+props.Title+"\n"+props.Artist+"\n"+props.AlbumTitle+"\n"+props.TrackNumber);

    public async Task<MediaSnapshot> Read(CancellationToken token=default)
    {
        await gate.WaitAsync(token);
        try
        {
            var session=await Session(token);
            if(session is null){previousArtwork=null;previousTrack=null;return new();}
            var props=await session.TryGetMediaPropertiesAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(2),token);
            var info=session.GetPlaybackInfo();
            var controls=info.Controls;
            var time=session.GetTimelineProperties();
            var sessionId=Hash(session.SourceAppUserModelId);
            var duration=Math.Max(0,(long)(time.EndTime-time.StartTime).TotalMilliseconds);
            var playing=info.PlaybackStatus==GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;
            var position=Math.Max(0,(long)(time.Position-time.StartTime).TotalMilliseconds);
            if(playing&&time.LastUpdatedTime> DateTimeOffset.MinValue)
            {
                var elapsed=Math.Clamp((DateTimeOffset.UtcNow-time.LastUpdatedTime).TotalMilliseconds,0,3600000);
                position+=(long)(elapsed*(info.PlaybackRate??1));
            }
            if(duration>0)position=Math.Min(position,duration);
            byte[]? art=await ReadArtwork(props.Thumbnail,token);
            var artId=art is null?"":Convert.ToHexString(SHA256.HashData(art))[..24];
            var trackId=Track(sessionId,props);
            var includeArt=artId!=previousArtwork||trackId!=previousTrack;
            previousArtwork=artId;
            previousTrack=trackId;
            return new()
            {
                Available=true,SessionId=sessionId,TrackId=trackId,Title=Limit(props.Title),Artist=Limit(props.Artist),Album=Limit(props.AlbumTitle),
                Player=PlayerName(session.SourceAppUserModelId),Playing=playing,PositionMs=position,DurationMs=duration,
                CanPlay=controls.IsPlayEnabled,CanPause=controls.IsPauseEnabled,CanPrevious=controls.IsPreviousEnabled,CanNext=controls.IsNextEnabled,
                CanStop=controls.IsStopEnabled,CanSeek=controls.IsPlaybackPositionEnabled&&duration>0,CanShuffle=controls.IsShuffleEnabled,CanRepeat=controls.IsRepeatEnabled,
                Shuffle=info.IsShuffleActive??false,Repeat=info.AutoRepeatMode switch {MediaPlaybackAutoRepeatMode.List=>"all",MediaPlaybackAutoRepeatMode.Track=>"one",_=>"off"},
                ArtworkId=artId,Artwork=includeArt&&art is not null?Convert.ToBase64String(art):null
            };
        }
        catch(OperationCanceledException)when(token.IsCancellationRequested){throw;}
        catch {previousArtwork=null;previousTrack=null;return new();}
        finally{gate.Release();}
    }

    private static string PlayerName(string id)
    {
        if(id.StartsWith("Spotify",StringComparison.OrdinalIgnoreCase))return "Spotify";
        if(id.StartsWith("Chrome",StringComparison.OrdinalIgnoreCase))return "Chrome";
        if(id.StartsWith("Microsoft.MicrosoftEdge",StringComparison.OrdinalIgnoreCase)||id.Equals("msedge.exe",StringComparison.OrdinalIgnoreCase))return "Microsoft Edge";
        return Limit(id);
    }

    private static async Task<byte[]?> ReadArtwork(IRandomAccessStreamReference? thumbnail,CancellationToken token)
    {
        if(thumbnail is null)return null;
        try
        {
            using var stream=await thumbnail.OpenReadAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(2),token);
            if(stream.Size==0||stream.Size>8*1024*1024)return null;
            using var reader=new DataReader(stream);
            var size=(uint)stream.Size;
            await reader.LoadAsync(size).AsTask().WaitAsync(TimeSpan.FromSeconds(2),token);
            var bytes=new byte[size];reader.ReadBytes(bytes);
            using var source=new MemoryStream(bytes);
            using var image=Image.FromStream(source);
            return EncodeArtwork(image);
        }
        catch(OperationCanceledException)when(token.IsCancellationRequested){throw;}
        catch{return null;}
    }

    internal static byte[]? EncodeArtwork(Image image)
    {
        if(image.Width>8192||image.Height>8192)return null;
        // Keep enlarged tablet artwork sharp without changing the protocol's byte cap.
        foreach(var edge in new[]{640,320})
        {
            var scale=Math.Min(1,(double)edge/Math.Max(image.Width,image.Height));
            using var bitmap=new Bitmap(Math.Max(1,(int)(image.Width*scale)),Math.Max(1,(int)(image.Height*scale)));
            using(var graphics=Graphics.FromImage(bitmap)){graphics.InterpolationMode=InterpolationMode.HighQualityBicubic;graphics.DrawImage(image,0,0,bitmap.Width,bitmap.Height);}
            using var output=new MemoryStream();bitmap.Save(output,ImageFormat.Jpeg);
            if(output.Length<=262144)return output.ToArray();
        }
        return null;
    }

    public async Task<string?> Command(JsonElement message,CancellationToken token)
    {
        await gate.WaitAsync(token);
        try
        {
            var session=await Session(token);
            if(session is null)return "No active media player.";
            var props=await session.TryGetMediaPropertiesAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(2),token);
            var id=Hash(session.SourceAppUserModelId);
            if(Protocol.String(message,"sessionId")!=id||Protocol.String(message,"trackId")!=Track(id,props))return "Track changed. Try again.";
            var controls=session.GetPlaybackInfo().Controls;
            var command=Protocol.String(message,"command",16);
            var allowed=command switch {"play"=>controls.IsPlayEnabled,"pause"=>controls.IsPauseEnabled,"previous"=>controls.IsPreviousEnabled,"next"=>controls.IsNextEnabled,"stop"=>controls.IsStopEnabled,"seek"=>controls.IsPlaybackPositionEnabled,"shuffle"=>controls.IsShuffleEnabled,"repeat"=>controls.IsRepeatEnabled,_=>false};
            if(!allowed)return "Player does not support this control.";
            Windows.Foundation.IAsyncOperation<bool> operation;
            switch(command)
            {
                case "play":operation=session.TryPlayAsync();break;
                case "pause":operation=session.TryPauseAsync();break;
                case "previous":operation=session.TrySkipPreviousAsync();break;
                case "next":operation=session.TrySkipNextAsync();break;
                case "stop":operation=session.TryStopAsync();break;
                case "shuffle":operation=session.TryChangeShuffleActiveAsync(Protocol.Bool(message,"enabled"));break;
                case "repeat":
                    var mode=Protocol.String(message,"mode",4) switch {"off"=>MediaPlaybackAutoRepeatMode.None,"all"=>MediaPlaybackAutoRepeatMode.List,"one"=>MediaPlaybackAutoRepeatMode.Track,_=>throw new ProtocolException("Invalid repeat mode.")};
                    operation=session.TryChangeAutoRepeatModeAsync(mode);break;
                case "seek":
                    var time=session.GetTimelineProperties();
                    var duration=Math.Max(0,(long)(time.EndTime-time.StartTime).TotalMilliseconds);
                    var position=Protocol.Long(message,"positionMs",0,duration);
                    operation=session.TryChangePlaybackPositionAsync(time.StartTime.Ticks+position*TimeSpan.TicksPerMillisecond);break;
                default:return "Unsupported media control.";
            }
            return await operation.AsTask().WaitAsync(TimeSpan.FromSeconds(3),token)?null:"Player declined this control.";
        }
        catch(OperationCanceledException)when(token.IsCancellationRequested){throw;}
        catch(ProtocolException ex){return ex.Message;}
        catch{return "Media player did not respond.";}
        finally{gate.Release();}
    }
}
