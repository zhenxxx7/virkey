namespace Virkey.Host;

internal static class Branding
{
    internal const string IconResource = "Virkey.Host.Assets.virkey.ico";

    public static Icon LoadIcon(int size = 32)
    {
        using var stream = typeof(Branding).Assembly.GetManifestResourceStream(IconResource)
            ?? throw new InvalidOperationException("The Virkey application icon is missing.");
        using var source = new Icon(stream, new Size(size, size));
        // The returned icon owns its data after the resource stream is closed.
        return (Icon)source.Clone();
    }
}
