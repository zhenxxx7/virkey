using System.Net.Security;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Virkey.Host;

internal sealed class ProtocolException(string message):Exception(message);

internal static class Protocol
{
    public const int Port=49372;
    public static readonly JsonSerializerOptions Json=new(){PropertyNamingPolicy=JsonNamingPolicy.CamelCase};
    public static string String(JsonElement value,string name,int max=256)
    {
        if(!value.TryGetProperty(name,out var item)||item.ValueKind!=JsonValueKind.String)throw new ProtocolException($"Missing {name}.");
        var result=item.GetString()??"";
        if(result.Length>max)throw new ProtocolException($"Invalid {name}.");
        return result;
    }
    public static int Int(JsonElement value,string name,int min,int max)
    {
        if(!value.TryGetProperty(name,out var item)||item.ValueKind!=JsonValueKind.Number||!item.TryGetInt32(out var number)||number<min||number>max)throw new ProtocolException($"Invalid {name}.");
        return number;
    }
    public static long Long(JsonElement value,string name,long min,long max)
    {
        if(!value.TryGetProperty(name,out var item)||item.ValueKind!=JsonValueKind.Number||!item.TryGetInt64(out var number)||number<min||number>max)throw new ProtocolException($"Invalid {name}.");
        return number;
    }
    public static bool Bool(JsonElement value,string name)
    {
        if(!value.TryGetProperty(name,out var item)||item.ValueKind is not (JsonValueKind.True or JsonValueKind.False))throw new ProtocolException($"Invalid {name}.");
        return item.GetBoolean();
    }
    public static bool PinMatches(string expected,string actual)=>actual.Length==6&&CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(expected),Encoding.UTF8.GetBytes(actual));
}

internal sealed class JsonLineReader(Stream stream)
{
    private readonly byte[] buffer=new byte[4096];
    private int offset,length;
    public async Task<JsonDocument?> Read(CancellationToken token)
    {
        using var line=new MemoryStream();
        while(true)
        {
            if(offset==length)
            {
                length=await stream.ReadAsync(buffer,token);offset=0;
                if(length==0){if(line.Length!=0)throw new ProtocolException("Incomplete message.");return null;}
            }
            int end=Array.IndexOf(buffer,(byte)'\n',offset,length-offset);
            int count=(end<0?length:end)-offset;
            if(line.Length+count>524288)throw new ProtocolException("Message too large.");
            line.Write(buffer,offset,count);offset+=count;
            if(end>=0)
            {
                offset++;
                try
                {
                    var document=JsonDocument.Parse(line.ToArray(),new(){MaxDepth=16});
                    if(document.RootElement.ValueKind!=JsonValueKind.Object){document.Dispose();throw new ProtocolException("Expected object.");}
                    return document;
                }
                catch(JsonException){throw new ProtocolException("Invalid JSON.");}
            }
        }
    }
}

internal sealed class AuthLimiter
{
    private readonly object gate=new();
    private readonly Queue<DateTimeOffset> failures=new();
    public bool Allowed(DateTimeOffset now)
    {
        lock(gate){while(failures.TryPeek(out var time)&&now-time>TimeSpan.FromSeconds(30))failures.Dequeue();return failures.Count<5;}
    }
    public void Failed(DateTimeOffset now){lock(gate){if(failures.Count<5)failures.Enqueue(now);}}
}
