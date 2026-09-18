using System.Runtime.InteropServices;

namespace Virkey.Host;

internal readonly record struct KeyCode(ushort Scan, bool Extended = false, ushort VirtualKey = 0);

internal static class InputMap
{
    private static readonly ushort[] Letters = [0x1e,0x30,0x2e,0x20,0x12,0x21,0x22,0x23,0x17,0x24,0x25,0x26,0x32,0x31,0x18,0x19,0x10,0x13,0x1f,0x14,0x16,0x2f,0x11,0x2d,0x15,0x2c];
    private static readonly Dictionary<int, KeyCode> Specials = new()
    {
        [0x28]=new(0x1c), [0x29]=new(1), [0x2a]=new(0x0e), [0x2b]=new(0x0f), [0x2c]=new(0x39),
        [0x2d]=new(0x0c), [0x2e]=new(0x0d), [0x2f]=new(0x1a), [0x30]=new(0x1b), [0x31]=new(0x2b),
        [0x32]=new(0x2b), [0x33]=new(0x27), [0x34]=new(0x28), [0x35]=new(0x29), [0x36]=new(0x33),
        [0x37]=new(0x34), [0x38]=new(0x35), [0x39]=new(0x3a), [0x46]=new(0x37,true), [0x47]=new(0x46),
        [0x48]=new(0,false,0x13), [0x49]=new(0x52,true), [0x4a]=new(0x47,true), [0x4b]=new(0x49,true),
        [0x4c]=new(0x53,true), [0x4d]=new(0x4f,true), [0x4e]=new(0x51,true), [0x4f]=new(0x4d,true),
        [0x50]=new(0x4b,true), [0x51]=new(0x50,true), [0x52]=new(0x48,true), [0x53]=new(0x45,true),
        [0x54]=new(0x35,true), [0x55]=new(0x37), [0x56]=new(0x4a), [0x57]=new(0x4e), [0x58]=new(0x1c,true),
        [0x59]=new(0x4f), [0x5a]=new(0x50), [0x5b]=new(0x51), [0x5c]=new(0x4b), [0x5d]=new(0x4c),
        [0x5e]=new(0x4d), [0x5f]=new(0x47), [0x60]=new(0x48), [0x61]=new(0x49), [0x62]=new(0x52),
        [0x63]=new(0x53), [0x64]=new(0x56), [0x65]=new(0x5d,true),
        [0xe0]=new(0x1d), [0xe1]=new(0x2a), [0xe2]=new(0x38), [0xe3]=new(0x5b,true),
        [0xe4]=new(0x1d,true), [0xe5]=new(0x36), [0xe6]=new(0x38,true), [0xe7]=new(0x5c,true)
    };
    public static bool TryKey(int usage, out KeyCode code)
    {
        if (usage is >=4 and <=29) { code=new(Letters[usage-4]); return true; }
        if (usage is >=0x1e and <=0x26) { code=new((ushort)(usage-0x1e+2)); return true; }
        if (usage==0x27) { code=new(0x0b); return true; }
        if (usage is >=0x3a and <=0x43) { code=new((ushort)(usage-0x3a+0x3b)); return true; }
        if (usage is 0x44 or 0x45) { code=new((ushort)(usage-0x44+0x57)); return true; }
        return Specials.TryGetValue(usage,out code);
    }
    public static bool TryMedia(int usage, out KeyCode code)
    {
        var vk=usage switch {0xe2=>0xad,0xea=>0xae,0xe9=>0xaf,0xb5=>0xb0,0xb6=>0xb1,0xb7=>0xb2,0xcd=>0xb3,_=>0};
        code=new(0,false,(ushort)vk); return vk!=0;
    }
}

internal interface IInputSink
{
    void Key(KeyCode code,bool down);
    void Button(int button,bool down);
    void Move(int dx,int dy);
    void Scroll(int amount);
}

internal sealed class InputSession(IInputSink sink, bool repeat = true, Func<int,Task>? repeatDelay = null) : IDisposable
{
    private readonly object gate=new();
    private readonly Dictionary<int,KeyCode> keys=new();
    private readonly Dictionary<int,KeyCode> media=new();
    private readonly HashSet<int> buttons=[];
    private int? repeatUsage;
    private long repeatGeneration;
    private bool closed;

    public void Key(int usage,bool down)
    {
        if(!InputMap.TryKey(usage,out var code)) throw new ProtocolException("Unsupported keyboard key.");
        lock(gate)
        {
            if(closed)return;
            if(down && keys.TryAdd(usage,code))
            {
                sink.Key(code,true);
                if(repeat && usage is >=4 and <0xe0 && usage is not (0x39 or 0x46 or 0x47 or 0x48 or 0x53))
                {
                    repeatUsage=usage;
                    StartRepeat(usage,++repeatGeneration);
                }
            }
            else if(!down && keys.Remove(usage))
            {
                if(repeatUsage==usage){repeatUsage=null;repeatGeneration++;}
                sink.Key(code,false);
            }
        }
    }
    private async void StartRepeat(int usage,long generation)
    {
        try
        {
            await (repeatDelay??Task.Delay)((SystemInformation.KeyboardDelay+1)*250);
            while(true)
            {
                lock(gate)
                {
                    if(closed||generation!=repeatGeneration||repeatUsage!=usage||!keys.TryGetValue(usage,out var code))return;
                    sink.Key(code,true);
                }
                await (repeatDelay??Task.Delay)(Math.Max(30,400-SystemInformation.KeyboardSpeed*12));
            }
        }
        catch { Release(); }
    }
    public void Media(int usage,bool down)
    {
        if(!InputMap.TryMedia(usage,out var code))throw new ProtocolException("Unsupported media key.");
        lock(gate)
        {
            if(closed)return;
            if(down&&media.TryAdd(usage,code))sink.Key(code,true);
            else if(!down&&media.Remove(usage))sink.Key(code,false);
        }
    }
    public void Button(int button,bool down)
    {
        if(button is <1 or >7)throw new ProtocolException("Unsupported mouse button.");
        lock(gate)
        {
            if(closed)return;
            foreach(var bit in new[]{1,2,4})
            {
                if((button&bit)==0)continue;
                if(down&&buttons.Add(bit))sink.Button(bit,true);
                else if(!down&&buttons.Remove(bit))sink.Button(bit,false);
            }
        }
    }
    public void Move(int dx,int dy){lock(gate){if(!closed)sink.Move(dx,dy);}}
    public void Scroll(int amount){lock(gate){if(!closed)sink.Scroll(amount);}}
    public void Release(){lock(gate){ReleaseLocked();}}
    private void ReleaseLocked()
    {
        repeatUsage=null;
        repeatGeneration++;
        foreach(var code in keys.Values.Concat(media.Values)) { try{sink.Key(code,false);}catch{} }
        foreach(var button in buttons) {try{sink.Button(button,false);}catch{}}
        keys.Clear();media.Clear();buttons.Clear();
    }
    public void Dispose(){lock(gate){closed=true;ReleaseLocked();}}
}

internal sealed class WindowsInput : IInputSink
{
    [StructLayout(LayoutKind.Sequential)] private struct INPUT {public uint type;public INPUTUNION data;}
    [StructLayout(LayoutKind.Explicit)] private struct INPUTUNION {[FieldOffset(0)]public MOUSEINPUT mouse;[FieldOffset(0)]public KEYBDINPUT key;}
    [StructLayout(LayoutKind.Sequential)] private struct MOUSEINPUT {public int dx,dy;public uint mouseData,dwFlags,time;public UIntPtr dwExtraInfo;}
    [StructLayout(LayoutKind.Sequential)] private struct KEYBDINPUT {public ushort wVk,wScan;public uint dwFlags,time;public UIntPtr dwExtraInfo;}
    [DllImport("user32.dll",SetLastError=true)] private static extern uint SendInput(uint count,INPUT[] inputs,int size);
    [DllImport("user32.dll")] private static extern short GetKeyState(int key);
    private static void Send(INPUT input)
    {
        if(SendInput(1,[input],Marshal.SizeOf<INPUT>())!=1)throw new InvalidOperationException("Windows blocked input. Run the target app at the same privilege level.");
    }
    public void Key(KeyCode code,bool down)=>Send(new(){type=1,data=new(){key=new(){wVk=code.VirtualKey,wScan=code.Scan,dwFlags=(code.VirtualKey==0?8u:0u)|(code.Extended?1u:0u)|(down?0u:2u)}}});
    public void Button(int button,bool down)=>Send(new(){type=0,data=new(){mouse=new(){dwFlags=(button,down) switch{(1,true)=>2,(1,false)=>4,(2,true)=>8,(2,false)=>16,(4,true)=>32,_=>64}}}});
    public void Move(int dx,int dy)=>Send(new(){type=0,data=new(){mouse=new(){dx=dx,dy=dy,dwFlags=1}}});
    public void Scroll(int amount)=>Send(new(){type=0,data=new(){mouse=new(){mouseData=unchecked((uint)(amount*120)),dwFlags=0x800}}});
    // GetKeyState reflects this thread's queue, so it cannot guarantee the
    // foreground app's current lock toggles. Advertise unknown feedback.
    public static object Leds(string type="leds")=>new{type,ledsKnown=false,capsLock=(GetKeyState(0x14)&1)!=0,numLock=(GetKeyState(0x90)&1)!=0};
    public static (bool Caps,bool Num) ReadLeds()=>((GetKeyState(0x14)&1)!=0,(GetKeyState(0x90)&1)!=0);
}
