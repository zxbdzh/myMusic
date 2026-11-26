using System.Text.Json.Nodes;
using Windows.Media;
using Windows.Media.Control;
using Windows.Media.Playback;

namespace MyMusic.SmtcBridge;

/// <summary>
/// SMTC（System Media Transport Controls）写入侧 + 系统侧回读。
///
/// 为什么用 <see cref="MediaPlayer.SystemMediaTransportControls"/> 而不是
/// <c>ISystemMediaTransportControlsInterop::GetForWindow</c>：
///   · GetForWindow 是 COM interop 接口，C#/WinRT 投影里没有公开类型，得自己声明
///     [ComImport] + vtable 顺序，任何一处顺序错就是进程级崩溃；
///   · MediaPlayer 路径是官方投影，解包（无 package identity）的 Win32 应用实测可用，
///     且不要求窗口句柄——桥作为独立进程没有自己的窗口，正好合适。
/// 实测记录见 docs/pc/SMTC-SPIKE.md。
/// </summary>
internal static class SmtcHost
{
    private static MediaPlayer? _player;
    private static SystemMediaTransportControls? _smtc;
    private static Action<string>? _onButton;

    public static void Start(Action<string> onButton)
    {
        _onButton = onButton;

        _player = new MediaPlayer();
        _smtc = _player.SystemMediaTransportControls
                ?? throw new InvalidOperationException("MediaPlayer.SystemMediaTransportControls 返回 null");

        // 四个按钮都开：SMTC 只显示被启用的按钮，全关就是一条空栏
        _smtc.IsEnabled = true;
        _smtc.IsPlayEnabled = true;
        _smtc.IsPauseEnabled = true;
        _smtc.IsStopEnabled = true;
        _smtc.IsNextEnabled = true;
        _smtc.IsPreviousEnabled = true;

        _smtc.ButtonPressed += OnButtonPressed;
    }

    public static void Stop()
    {
        if (_smtc != null) _smtc.ButtonPressed -= OnButtonPressed;
        _smtc = null;
        _player?.Dispose();
        _player = null;
    }

    private static void OnButtonPressed(SystemMediaTransportControls sender,
        SystemMediaTransportControlsButtonPressedEventArgs args)
    {
        // 只转发语义，不在这里改状态：真正的播放状态由 JVM 侧回推 update，
        // 否则系统浮层和播放器会出现"两边各说各话"的竞态
        var name = args.Button switch
        {
            SystemMediaTransportControlsButton.Play => "play",
            SystemMediaTransportControlsButton.Pause => "pause",
            SystemMediaTransportControlsButton.Stop => "stop",
            SystemMediaTransportControlsButton.Next => "next",
            SystemMediaTransportControlsButton.Previous => "previous",
            _ => null,
        };
        if (name != null) _onButton?.Invoke(name);
    }

    public static void Update(
        string status, string title, string artist, string album,
        long durationMs, long positionMs, bool canSeek, bool canNext, bool canPrev)
    {
        if (_smtc == null) throw new InvalidOperationException("SMTC 未初始化");

        _smtc.IsNextEnabled = canNext || true;     // 媒体浮层的按钮常亮，语义交给 JVM 判断
        _smtc.IsPreviousEnabled = canPrev || true;
        _smtc.PlaybackStatus = status switch
        {
            "playing" => MediaPlaybackStatus.Playing,
            "paused" => MediaPlaybackStatus.Paused,
            _ => MediaPlaybackStatus.Stopped,
        };

        var updater = _smtc.DisplayUpdater;
        updater.Type = MediaPlaybackType.Music;
        updater.MusicProperties.Title = title;
        updater.MusicProperties.Artist = artist;
        updater.MusicProperties.AlbumTitle = album;
        updater.Update();

        // 进度条：Windows 10 1607+ 才有 UpdateTimelineProperties；老系统上抛异常不应影响主流程
        try
        {
            var timeline = new SystemMediaTransportControlsTimelineProperties
            {
                StartTime = TimeSpan.Zero,
                EndTime = TimeSpan.FromMilliseconds(Math.Max(durationMs, 0)),
                Position = TimeSpan.FromMilliseconds(Math.Max(positionMs, 0)),
            };
            _smtc.UpdateTimelineProperties(timeline);
        }
        catch (Exception)
        {
            // 系统版本过老：进度条缺失可接受
        }
    }

    /// <summary>
    /// 从系统侧（GSMTCS）回读当前会话，用来证明"Windows 媒体浮层确实看得见我们"。
    /// 这是只读 API，与写入侧的 MediaPlayer 无关，因此自检时不会自证。
    /// </summary>
    public static JsonObject Probe()
    {
        var result = new JsonObject { ["event"] = "probe" };
        try
        {
            var manager = GlobalSystemMediaTransportControlsSessionManager.RequestAsync()
                .AsTask().GetAwaiter().GetResult();
            var session = manager.GetCurrentSession();
            if (session == null)
            {
                result["source"] = "";
                result["title"] = "";
                result["artist"] = "";
                result["album"] = "";
                result["status"] = "none";
                return result;
            }

            var props = session.TryGetMediaPropertiesAsync().AsTask().GetAwaiter().GetResult();
            result["source"] = session.SourceAppUserModelId;
            result["title"] = props.Title ?? "";
            result["artist"] = props.Artist ?? "";
            result["album"] = props.AlbumTitle ?? "";
            result["status"] = session.GetPlaybackInfo().PlaybackStatus.ToString();
        }
        catch (Exception ex)
        {
            result["error"] = ex.GetType().Name + ": " + ex.Message;
        }
        return result;
    }
}
