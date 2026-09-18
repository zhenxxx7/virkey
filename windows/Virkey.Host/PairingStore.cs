using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;

namespace Virkey.Host;

internal sealed record RememberedDevice(string Id,string TokenHash);
internal sealed record PairingData(int Version,string Certificate,RememberedDevice[] Devices);
internal sealed record PairingCredential(string DeviceId,string Token)
{public override string ToString()=>"PairingCredential([redacted])";}

/// <summary>Persistent PC identity plus token hashes, protected for this Windows user by DPAPI.</summary>
internal sealed class PairingStore:IDisposable
{
    private static readonly byte[] Entropy=Encoding.UTF8.GetBytes("Virkey host pairing v1");
    private readonly string? path;
    private readonly FileStream? lease;
    private PairingData data;
    public int Count=>data.Devices.Length;

    public PairingStore(string? path=null)
    {
        this.path=path;
        try
        {
            if(path is not null)
            {
                Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
                // Do not let a second host overwrite a running host's revocation state.
                lease=new FileStream(path+".lock",FileMode.OpenOrCreate,FileAccess.ReadWrite,FileShare.None);
            }
            if(path is not null&&File.Exists(path))
            {
                if(new FileInfo(path).Length>65536)throw new InvalidDataException("Pairing data exceeds its size limit.");
                var plaintext=ProtectedData.Unprotect(File.ReadAllBytes(path),Entropy,DataProtectionScope.CurrentUser);
                try{data=JsonSerializer.Deserialize<PairingData>(plaintext,Protocol.Json)??throw new InvalidDataException("Invalid pairing data.");}
                finally{CryptographicOperations.ZeroMemory(plaintext);}
                if(data.Version!=1||data.Certificate is null||data.Certificate.Length>20000||data.Devices is null||data.Devices.Length>16||
                    data.Devices.Any(d=>d is null||!ValidHex(d.Id,32)||!ValidHex(d.TokenHash,64)))throw new InvalidDataException("Invalid pairing data.");
                using var cert=OpenCertificate();
                if(!cert.HasPrivateKey||cert.NotAfter.ToUniversalTime()<=DateTime.UtcNow)
                    throw new InvalidDataException("Saved host identity is expired or invalid. See the pairing recovery guide.");
            }
            else
            {
                using var key=RSA.Create(2048);
                var request=new CertificateRequest("CN=Virkey Host",key,HashAlgorithmName.SHA256,RSASignaturePadding.Pkcs1);
                request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false,false,0,true));
                request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature,true));
                request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection{new("1.3.6.1.5.5.7.3.1")},false));
                using var created=request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1),DateTimeOffset.UtcNow.AddYears(5));
                data=new(1,Convert.ToBase64String(created.Export(X509ContentType.Pfx)),[]);
                Save(data);
            }
        }
        catch{lease?.Dispose();throw;}
    }

    public static PairingStore Default()=>new(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"Virkey","pairing.dat"));
    // Schannel uses a temporary user key container, removed when this certificate is disposed.
    public X509Certificate2 OpenCertificate()=>new(Convert.FromBase64String(data.Certificate),(string?)null,X509KeyStorageFlags.UserKeySet);
    private static bool ValidHex(string? value,int length)=>value is not null&&value.Length==length&&value.All(Uri.IsHexDigit);
    public bool Authenticate(string id,string token)
    {
        if(!ValidHex(id,32)||!ValidHex(token,64))return false;
        var device=data.Devices.FirstOrDefault(d=>string.Equals(d.Id,id,StringComparison.OrdinalIgnoreCase));
        if(device is null)return false;
        var hash=SHA256.HashData(Convert.FromHexString(token));
        return CryptographicOperations.FixedTimeEquals(hash,Convert.FromHexString(device.TokenHash));
    }
    public PairingCredential Issue()
    {
        var credential=new PairingCredential(Guid.NewGuid().ToString("N"),Convert.ToHexString(RandomNumberGenerator.GetBytes(32)));
        var device=new RememberedDevice(credential.DeviceId,Convert.ToHexString(SHA256.HashData(Convert.FromHexString(credential.Token))));
        var next=data with{Devices=data.Devices.TakeLast(15).Append(device).ToArray()};
        Save(next);data=next;
        return credential;
    }
    public void RevokeAll(){var next=data with{Devices=[]};Save(next);data=next;}
    private void Save(PairingData next)
    {
        if(path is null)return;
        var plaintext=JsonSerializer.SerializeToUtf8Bytes(next,Protocol.Json);
        byte[] encrypted;
        try{encrypted=ProtectedData.Protect(plaintext,Entropy,DataProtectionScope.CurrentUser);}
        finally{CryptographicOperations.ZeroMemory(plaintext);}
        var temporary=path+".tmp";
        using(var file=new FileStream(temporary,FileMode.Create,FileAccess.Write,FileShare.None))
        {file.Write(encrypted);file.Flush(true);}
        File.Move(temporary,path,true);
    }
    public void Dispose()=>lease?.Dispose();
}
