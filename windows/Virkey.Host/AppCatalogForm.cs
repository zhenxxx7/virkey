namespace Virkey.Host;

internal sealed class AppCatalogForm : Form
{
    private readonly Icon applicationIcon = Branding.LoadIcon();

    public AppCatalogForm(AppCatalog apps)
    {
        Text = "Virkey · Extra apps";
        Icon = applicationIcon;
        Size = new Size(660, 400);
        StartPosition = FormStartPosition.CenterParent;
        Font = new Font("Segoe UI", 10);
        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(18), RowCount = 3, ColumnCount = 1 };
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        Controls.Add(layout);
        layout.Controls.Add(new Label { AutoSize = true, MaximumSize = new Size(580, 0), Text = "Start-menu apps appear automatically. Add portable apps or shortcuts here, then refresh Apps on your tablet." });
        var list = new ListBox { Dock = DockStyle.Fill, HorizontalScrollbar = true, Margin = new Padding(0, 16, 0, 16) };
        layout.Controls.Add(list);
        void RefreshList() { list.Items.Clear(); list.Items.AddRange(apps.ManualPaths); }
        var buttons = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill };
        var add = new Button { Text = "Add app…", AutoSize = true };
        var remove = new Button { Text = "Remove", AutoSize = true };
        var done = new Button { Text = "Done", AutoSize = true };
        buttons.Controls.AddRange([add, remove, done]);
        layout.Controls.Add(buttons);
        add.Click += (_, _) =>
        {
            using var picker = new OpenFileDialog { Filter = "Apps and shortcuts|*.exe;*.lnk;*.url", DereferenceLinks = false, CheckFileExists = true };
            if (picker.ShowDialog(this) != DialogResult.OK) return;
            try { apps.Add(picker.FileName); RefreshList(); }
            catch (Exception error) { MessageBox.Show(this, error.Message, "Could not add app"); }
        };
        remove.Click += (_, _) =>
        {
            if (list.SelectedItem is not string path) return;
            try { apps.Remove(path); RefreshList(); }
            catch (Exception error) { MessageBox.Show(this, error.Message, "Could not save apps"); }
        };
        done.Click += (_, _) => Close();
        RefreshList();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) { Icon = null; applicationIcon.Dispose(); }
        base.Dispose(disposing);
    }
}
