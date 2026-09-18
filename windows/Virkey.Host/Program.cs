namespace Virkey.Host;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        if(args.Contains("--self-test")||args.Contains("--smoke-test")||args.Contains("--render-preview")||args.Contains("--apps-smoke-test"))
        {
            var reportIndex=Array.IndexOf(args,"--report");
            var reportPath=reportIndex>=0&&reportIndex+1<args.Length?args[reportIndex+1]:Path.Combine(AppContext.BaseDirectory,"host-test-results.txt");
            var lines=new List<string>();
            try
            {
                if(args.Contains("--render-preview"))
                {
                    var output=args[Array.IndexOf(args,"--render-preview")+1];
                    ApplicationConfiguration.Initialize();
                    using var form=new HostForm(preview:true);
                    form.ShowInTaskbar=false;form.StartPosition=FormStartPosition.Manual;
                    form.Location=new Point(-32000,-32000);form.Opacity=0;
                    form.Show();Application.DoEvents();form.PerformLayout();
                    using var image=new Bitmap(form.Width,form.Height);
                    form.DrawToBitmap(image,new Rectangle(0,0,image.Width,image.Height));
                    image.Save(output,System.Drawing.Imaging.ImageFormat.Png);
                    lines.Add("PASS hidden host UI render, example connection details; no server started.");
                }
                else if(args.Contains("--apps-smoke-test"))
                {
                    var apps=new AppCatalog([Environment.GetFolderPath(Environment.SpecialFolder.Programs),Environment.GetFolderPath(Environment.SpecialFolder.CommonPrograms)]);
                    using var timeout=new CancellationTokenSource(TimeSpan.FromSeconds(20));
                    var found=Task.Run(()=>apps.List(timeout.Token),timeout.Token).WaitAsync(TimeSpan.FromSeconds(20)).GetAwaiter().GetResult();
                    lines.Add($"PASS Windows app discovery; apps={found.Count}; icons={found.Count(app=>app.Icon.Length>0)}.");
                    lines.Add("Read-only smoke test: no apps launched or user settings changed.");
                }
                else if(args.Contains("--self-test"))SelfTests.Run(lines.Add).GetAwaiter().GetResult();
                else
                {
                    var media=new MediaBridge();
                    using var timeout=new CancellationTokenSource(TimeSpan.FromSeconds(12));
                    var result=media.Read(timeout.Token).GetAwaiter().GetResult();
                    lines.Add($"PASS Windows media read; available={result.Available}; player={result.Player}; artwork={result.Artwork is not null}; timeline={result.PositionMs}/{result.DurationMs} ms.");
                    lines.Add("Read-only smoke test: no input or media commands sent.");
                }
                lines.Add("PASS all checks");
                File.WriteAllLines(reportPath,lines);
                return 0;
            }
            catch(Exception ex)
            {
                lines.Add($"FAIL {ex}");
                File.WriteAllLines(reportPath,lines);
                return 1;
            }
        }
        ApplicationConfiguration.Initialize();
        try
        {
            using var form=new HostForm();
            Application.Run(form);
            return 0;
        }
        catch(Exception error)when(error is IOException or System.Security.Cryptography.CryptographicException or System.Text.Json.JsonException)
        {
            MessageBox.Show("Could not open Virkey's saved settings. Close any other Virkey Host instance and use the same Windows account. If pairing data is damaged, back it up before resetting and pair your tablet again.\n\n"+error.Message,
                "Virkey Host",MessageBoxButtons.OK,MessageBoxIcon.Error);
            return 1;
        }
    }
}
