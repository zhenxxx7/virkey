using System.Diagnostics;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Virkey.Host;

internal sealed record AppEntry(string Id, string Name, string Icon);
internal sealed record AppSettings(string PcId, string[] Paths);

/// <summary>The tablet can launch only IDs discovered or explicitly added on this PC.</summary>
internal sealed class AppCatalog
{
    private readonly object gate = new();
    private readonly string[] roots;
    private readonly string? settingsPath;
    private readonly Action<string> launch;
    private readonly Dictionary<string, string> targets = new();
    private AppSettings settings;
    public string PcId => settings.PcId;
    public string[] ManualPaths { get { lock (gate) return settings.Paths.ToArray(); } }

    public AppCatalog(string[] roots, string? settingsPath = null, Action<string>? launch = null)
    {
        this.roots = roots;
        this.settingsPath = settingsPath;
        this.launch = launch ?? (path => Process.Start(new ProcessStartInfo(path) { UseShellExecute = true }));
        settings = new(Guid.NewGuid().ToString("N"), []);
        if (settingsPath is not null && File.Exists(settingsPath))
        {
            var text = File.ReadAllText(settingsPath);
            if (text.Length > 65536) throw new InvalidOperationException("Virkey app settings are too large.");
            var stored = JsonSerializer.Deserialize<AppSettings>(text, Protocol.Json);
            if (stored is not null && Guid.TryParse(stored.PcId, out _) && stored.Paths is not null)
                settings = stored with { Paths = stored.Paths.Where(IsLocalTarget).Distinct(StringComparer.OrdinalIgnoreCase).Take(48).ToArray() };
        }
        if (settingsPath is not null && !File.Exists(settingsPath)) Save();
    }

    public static AppCatalog Default() => new(
        [Environment.GetFolderPath(Environment.SpecialFolder.Programs), Environment.GetFolderPath(Environment.SpecialFolder.CommonPrograms)],
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Virkey", "apps.json"));

    internal static bool IsLocalTarget(string path)
    {
        if (string.IsNullOrWhiteSpace(path) || path.Length > 1024 || !Path.IsPathFullyQualified(path) || path.StartsWith(@"\\")) return false;
        return Path.GetExtension(path).ToLowerInvariant() is ".exe" or ".lnk" or ".url";
    }

    public void Add(string path)
    {
        path = Path.GetFullPath(path);
        if (!IsLocalTarget(path) || !File.Exists(path)) throw new InvalidOperationException("Choose a local application or Windows shortcut.");
        lock (gate)
        {
            if (settings.Paths.Contains(path, StringComparer.OrdinalIgnoreCase)) return;
            if (settings.Paths.Length >= 48) throw new InvalidOperationException("Up to 48 extra apps can be added.");
            var updated = settings with { Paths = settings.Paths.Append(path).ToArray() };
            Save(updated);
            settings = updated;
        }
    }

    public void Remove(string path)
    {
        lock (gate)
        {
            var updated = settings with { Paths = settings.Paths.Where(p => !p.Equals(path, StringComparison.OrdinalIgnoreCase)).ToArray() };
            Save(updated);
            settings = updated;
            targets.Clear();
        }
    }

    private void Save(AppSettings? next = null)
    {
        if (settingsPath is null) return;
        Directory.CreateDirectory(Path.GetDirectoryName(settingsPath)!);
        var temporary = settingsPath + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(next ?? settings, Protocol.Json));
        File.Move(temporary, settingsPath, true);
    }

    public IReadOnlyList<AppEntry> List(CancellationToken token, bool icons = true)
    {
        var paths = new HashSet<string>(ManualPaths.Where(File.Exists), StringComparer.OrdinalIgnoreCase);
        foreach (var root in roots)
        {
            if (!Directory.Exists(root)) continue;
            var options = new EnumerationOptions { RecurseSubdirectories = true, IgnoreInaccessible = true, AttributesToSkip = FileAttributes.ReparsePoint };
            foreach (var path in Directory.EnumerateFiles(root, "*", options))
            {
                token.ThrowIfCancellationRequested();
                if (paths.Count >= 256) break;
                if (Path.GetExtension(path).ToLowerInvariant() is ".lnk" or ".url") paths.Add(path);
            }
        }
        var found = paths.OrderBy(Path.GetFileNameWithoutExtension, StringComparer.OrdinalIgnoreCase).Select(path =>
            (Id: Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(Path.GetFullPath(path).ToUpperInvariant())))[..32], Path: path)).ToArray();
        lock (gate)
        {
            targets.Clear();
            foreach (var item in found) targets[item.Id] = item.Path;
        }
        return found.Select(item =>
        {
            token.ThrowIfCancellationRequested();
            var name = Path.GetFileNameWithoutExtension(item.Path);
            return new AppEntry(item.Id, name[..Math.Min(name.Length, 120)], icons ? ReadIcon(item.Path) : "");
        }).ToArray();
    }

    public void Launch(string pcId, string id, CancellationToken token)
    {
        if (pcId != PcId) throw new InvalidOperationException("This dock button belongs to a different PC.");
        string? path;
        lock (gate) targets.TryGetValue(id, out path);
        if (path is null) throw new InvalidOperationException("App is unavailable. Refresh the app list or add it in Virkey Host.");
        token.ThrowIfCancellationRequested();
        if (!IsLocalTarget(path) || !File.Exists(path)) throw new InvalidOperationException("App shortcut is no longer available.");
        launch(path);
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct ShellFileInfo
    {
        public IntPtr Icon;
        public int IconIndex;
        public uint Attributes;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string DisplayName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 80)] public string TypeName;
    }
    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr SHGetFileInfo(string path, uint attributes, ref ShellFileInfo info, uint size, uint flags);
    [DllImport("user32.dll")] private static extern bool DestroyIcon(IntPtr icon);
    private static string ReadIcon(string path)
    {
        var info = new ShellFileInfo();
        try
        {
            if (SHGetFileInfo(path, 0, ref info, (uint)Marshal.SizeOf<ShellFileInfo>(), 0x100) == IntPtr.Zero || info.Icon == IntPtr.Zero) return "";
            using var icon = Icon.FromHandle(info.Icon);
            using var bitmap = icon.ToBitmap();
            using var output = new MemoryStream();
            bitmap.Save(output, ImageFormat.Png);
            return output.Length <= 32768 ? Convert.ToBase64String(output.ToArray()) : "";
        }
        catch { return ""; }
        finally { if (info.Icon != IntPtr.Zero) DestroyIcon(info.Icon); }
    }
}
