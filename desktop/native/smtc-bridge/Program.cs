using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace MyMusic.SmtcBridge;

/// <summary>
/// 桥的入口：stdin 收 JSON 行命令，stdout 发 JSON 行事件。
///
/// 为什么用标准输入输出而不是命名管道/本地 socket：
///   · 管道随父进程生命周期结束（父进程死 → stdin EOF → 本进程退出），不会留下孤儿；
///   · 不占端口，不需要防火墙放行，也不需要处理 ACL；
///   · jpackage 打出来的 exe 直接 ProcessBuilder 拉起即可。
///
/// 协议（每行一个 JSON 对象，UTF-8 无 BOM）：
///   JVM → 桥：{"op":"update","status":"playing|paused|stopped","title":..,"artist":..,
///              "album":..,"durationMs":0,"positionMs":0,"canSeek":true,"canNext":true,"canPrev":true}
///             {"op":"probe"}      —— 让桥从系统侧（GSMTCS）回读当前会话，用于自检
///             {"op":"quit"}
///   桥 → JVM：{"event":"ready"} / {"event":"error","message":..}
///             {"event":"button","button":"play|pause|toggle|next|previous|stop"}
///             {"event":"probe","source":..,"title":..,"artist":..,"album":..,"status":..}
/// </summary>
internal static class Program
{
    private static readonly object WriteLock = new();
    private static StreamWriter _out = null!;

    private static int Main()
    {
        // 无 BOM 的 UTF-8：JVM 侧按行读，带 BOM 会让第一行 JSON 解析失败
        _out = new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false))
        {
            AutoFlush = true,
            NewLine = "\n",
        };
        Console.InputEncoding = Encoding.UTF8;

        try
        {
            SmtcHost.Start(OnButton);
            Send(new JsonObject { ["event"] = "ready" });
        }
        catch (Exception ex)
        {
            // 桥不可用时 JVM 侧要能明确知道原因，而不是静默无浮层
            Send(new JsonObject { ["event"] = "error", ["message"] = ex.GetType().Name + ": " + ex.Message });
            return 1;
        }

        string? line;
        while ((line = Console.ReadLine()) != null)
        {
            if (line.Length == 0) continue;
            try
            {
                Handle(line);
            }
            catch (Exception ex)
            {
                Send(new JsonObject { ["event"] = "error", ["message"] = ex.GetType().Name + ": " + ex.Message });
            }
        }

        SmtcHost.Stop();
        return 0;
    }

    private static void Handle(string line)
    {
        var doc = JsonNode.Parse(line)?.AsObject();
        if (doc == null) return;
        switch ((string?)doc["op"])
        {
            case "update":
                SmtcHost.Update(
                    status: (string?)doc["status"] ?? "stopped",
                    title: (string?)doc["title"] ?? "",
                    artist: (string?)doc["artist"] ?? "",
                    album: (string?)doc["album"] ?? "",
                    durationMs: (long?)doc["durationMs"] ?? 0,
                    positionMs: (long?)doc["positionMs"] ?? 0,
                    canSeek: (bool?)doc["canSeek"] ?? false,
                    canNext: (bool?)doc["canNext"] ?? false,
                    canPrev: (bool?)doc["canPrev"] ?? false);
                break;
            case "probe":
                Send(SmtcHost.Probe());
                break;
            case "quit":
                Environment.Exit(0);
                break;
        }
    }

    private static void OnButton(string button) =>
        Send(new JsonObject { ["event"] = "button", ["button"] = button });

    private static void Send(JsonObject payload)
    {
        var text = payload.ToJsonString(new JsonSerializerOptions { Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping });
        lock (WriteLock)
        {
            _out.WriteLine(text);
        }
    }
}
