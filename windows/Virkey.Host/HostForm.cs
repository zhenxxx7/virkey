using Microsoft.Win32;

namespace Virkey.Host;

internal sealed class HostForm:Form
{
    private readonly HostServer server;
    private readonly NotifyIcon tray;
    private readonly Icon applicationIcon;
    private readonly Label status=new(){AutoSize=true,Text="Stopped"};
    private readonly TextBox addresses=new(){ReadOnly=true,Multiline=true,Height=60,BorderStyle=BorderStyle.None,BackColor=Color.FromArgb(27,32,35),ForeColor=Color.White};
    private readonly Label pin=new(){AutoSize=true,Font=new Font("Segoe UI",28,FontStyle.Bold)};
    private readonly Label fingerprint=new(){AutoSize=true,Font=new Font("Consolas",15)};
    private readonly Button start=new(){Text="Start host",AutoSize=true};
    private readonly Button reset=new(){Text="Reset pairing",AutoSize=true};
    private readonly Button appsButton=new(){Text="Extra apps…",AutoSize=true};
    private bool exiting;
    private bool cleaned;
    private readonly bool preview;

    public HostForm(bool preview=false)
    {
        this.preview=preview;
        server=new HostServer(apps:preview?new AppCatalog([]):null,pairings:preview?new PairingStore():null);
        Text="Virkey Host";StartPosition=FormStartPosition.CenterScreen;
        MinimumSize=new Size(620,680);Size=new Size(700,760);
        BackColor=Color.FromArgb(27,32,35);ForeColor=Color.FromArgb(234,237,231);
        applicationIcon=Branding.LoadIcon();
        Font=new Font("Segoe UI",10);Icon=applicationIcon;
        foreach(var button in new[]{start,reset,appsButton})
        {
            button.FlatStyle=FlatStyle.Flat;button.BackColor=Color.FromArgb(43,50,54);
            button.ForeColor=Color.FromArgb(234,237,231);button.Padding=new Padding(12,6,12,6);
            button.FlatAppearance.BorderColor=Color.FromArgb(59,68,71);button.Margin=new Padding(0,0,12,0);
        }
        var layout=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=1,Padding=new Padding(28),AutoScroll=true};
        Controls.Add(layout);
        void Add(Control control){control.Margin=new Padding(0,0,0,14);control.Anchor=AnchorStyles.Left|AnchorStyles.Right;layout.Controls.Add(control);}
        Add(new Label{Text="VIRKEY  /  WINDOWS HOST",AutoSize=true,Font=new Font("Segoe UI",16,FontStyle.Bold),ForeColor=Color.FromArgb(142,220,192)});
        Add(new Label{Text="Connect your tablet and PC to the same network. Pair once, then reconnect without a PIN. Reset pairing forgets trusted tablets.",AutoSize=true,MaximumSize=new Size(580,0)});
        Add(status);
        Add(new Label{Text="PC address",AutoSize=true});
        Add(addresses);
        Add(new Label{Text="Pairing PIN",AutoSize=true});
        Add(pin);
        Add(new Label{Text="Compare this fingerprint on your tablet before trusting the connection",AutoSize=true});
        Add(fingerprint);
        var buttons=new FlowLayoutPanel{AutoSize=true,FlowDirection=FlowDirection.LeftToRight};
        appsButton.Click+=(_,_)=>{using var dialog=new AppCatalogForm(server.Apps);dialog.ShowDialog(this);};
        buttons.Controls.Add(start);buttons.Controls.Add(reset);buttons.Controls.Add(appsButton);Add(buttons);
        Add(new Label{Text="If Windows asks, allow access on your private network. Closing this window keeps the host in the system tray. Stop or Exit releases all held input.",AutoSize=true,MaximumSize=new Size(580,0),ForeColor=Color.FromArgb(145,156,157),Font=new Font("Segoe UI",9)});
        var menu=new ContextMenuStrip();
        menu.Items.Add("Show Virkey Host",null,(_,_)=>ShowWindow());
        menu.Items.Add("Stop host",null,(_,_)=>server.Stop());
        menu.Items.Add("Exit",null,(_,_)=>{exiting=true;Close();});
        tray=new NotifyIcon{Icon=Icon,Text="Virkey Host",ContextMenuStrip=menu,Visible=!preview};
        tray.DoubleClick+=(_,_)=>ShowWindow();
        start.Click+=(_,_)=>
        {
            try{if(server.Running)server.Stop();else server.Start();RefreshDetails();}
            catch(Exception ex){status.Text="Could not start: "+ex.Message;RefreshDetails();}
        };
        reset.Click+=(_,_)=>{
            if(MessageBox.Show(this,"Forget all trusted tablets, disconnect input and generate a new pairing PIN?","Reset pairing",MessageBoxButtons.YesNo,MessageBoxIcon.Warning)!=DialogResult.Yes)return;
            try{server.ResetPairing();RefreshDetails();}catch(Exception){status.Text="Could not save pairing reset. Stop the host and check file permissions.";}
        };
        server.StatusChanged+=OnStatus;
        if(!preview)SystemEvents.PowerModeChanged+=PowerChanged;
        RefreshDetails();
    }
    private void ShowWindow(){Show();WindowState=FormWindowState.Normal;Activate();RefreshDetails();}
    private void RefreshDetails()
    {
        addresses.Text=preview?"192.168.1.10:49372":string.Join(Environment.NewLine,HostServer.Addresses().Select(address=>$"{address}:{server.Port}"));
        if(addresses.Text.Length==0)addresses.Text="No network address. Connect to Wi-Fi or Ethernet.";
        pin.Text=preview?"123456":server.Pin;fingerprint.Text=preview?"A1B2 C3D4 E5F6":server.ShortFingerprint;
        start.Text=server.Running?"Stop host":"Start host";
    }
    private void OnStatus(string value)
    {
        if(IsDisposed||Disposing)return;
        if(InvokeRequired){try{BeginInvoke(()=>OnStatus(value));}catch(InvalidOperationException){}return;}
        status.Text=value;RefreshDetails();
        tray.Text=server.Running?"Virkey Host · running":"Virkey Host · stopped";
    }
    private void PowerChanged(object sender,PowerModeChangedEventArgs args)
    {
        if(args.Mode==PowerModes.Suspend)server.Stop();
    }
    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if(!preview&&!exiting&&e.CloseReason==CloseReason.UserClosing){e.Cancel=true;Hide();return;}
        Cleanup();
        base.OnFormClosing(e);
    }
    private void Cleanup()
    {
        if(cleaned)return;cleaned=true;
        server.StatusChanged-=OnStatus;SystemEvents.PowerModeChanged-=PowerChanged;
        server.Dispose();tray.Visible=false;tray.Dispose();
    }
    protected override void Dispose(bool disposing)
    {
        if(disposing){Cleanup();Icon=null;applicationIcon.Dispose();}
        base.Dispose(disposing);
    }
}
