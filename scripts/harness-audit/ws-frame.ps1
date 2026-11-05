# HARNESS-AUDIT-1 probe: send ONE owner frame to the test daemon and print the replies for a few seconds.
# usage: pwsh ws-frame.ps1 -State <state dir> -Type <frame type> -Data '<json payload>' [-Port 8812] [-Seconds 3]
param([string]$State, [string]$Type, [string]$Data = '{}', [int]$Port = 8812, [int]$Seconds = 3)
$ws = [System.Net.WebSockets.ClientWebSocket]::new()
$ws.Options.SetRequestHeader('X-Infantry-Owner', (Get-Content "$State/owner.token" -Raw).Trim())
$ws.ConnectAsync([Uri]"ws://127.0.0.1:$Port/ws", [Threading.CancellationToken]::None).Wait()
$frame = @{ type = $Type; data = $Data; requestId = 'audit' } | ConvertTo-Json -Compress
$bytes = [Text.Encoding]::UTF8.GetBytes($frame)
$ws.SendAsync([ArraySegment[byte]]::new($bytes), 'Text', $true, [Threading.CancellationToken]::None).Wait()
$buf = [byte[]]::new(1MB); $end = (Get-Date).AddSeconds($Seconds)
while ((Get-Date) -lt $end) {
  $cts = [Threading.CancellationTokenSource]::new(1000)
  try { $r = $ws.ReceiveAsync([ArraySegment[byte]]::new($buf), $cts.Token).Result
        $t = [Text.Encoding]::UTF8.GetString($buf, 0, $r.Count); if ($t -match '"requestId":"audit"') { $t.Substring(0, [Math]::Min(600, $t.Length)) } } catch { }
}
