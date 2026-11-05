# ============================================================
# Windows 7 All-in-One Optimizer
# 适用：Windows 7 SP1 / PowerShell 2.0+
# 说明：安全优先，结束进程/禁用服务均需手动选择确认
# ============================================================

$ErrorActionPreference = "SilentlyContinue"

$Script:Stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$Script:Desktop = [Environment]::GetFolderPath("Desktop")
if (-not (Test-Path $Script:Desktop)) { $Script:Desktop = $env:USERPROFILE }
$Script:LogFile = Join-Path $Script:Desktop "Win7_Optimize_Log.txt"
$Script:ReportFile = Join-Path $Script:Desktop "Win7_Diagnostic_Report.txt"
$Script:BackupRoot = Join-Path $env:ProgramData "Win7Optimizer"
$Script:BackupDir = $null
$Script:TweaksBackedUp = $false
$Script:HighPerformanceGuid = "8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c"

$Script:VisualSettings = @(
    @{ Path = "HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\VisualEffects"; Name = "VisualFXSetting"; Type = "REG_DWORD"; New = "2" },
    @{ Path = "HKCU\Control Panel\Desktop"; Name = "DragFullWindows"; Type = "REG_SZ"; New = "0" },
    @{ Path = "HKCU\Control Panel\Desktop"; Name = "MenuShowDelay"; Type = "REG_SZ"; New = "0" },
    @{ Path = "HKCU\Control Panel\Desktop"; Name = "MinAnimate"; Type = "REG_SZ"; New = "0" },
    @{ Path = "HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\Advanced"; Name = "ListviewAlphaSelect"; Type = "REG_DWORD"; New = "0" },
    @{ Path = "HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\Advanced"; Name = "ListviewShadow"; Type = "REG_DWORD"; New = "0" },
    @{ Path = "HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\Advanced"; Name = "TaskbarAnimations"; Type = "REG_DWORD"; New = "0" },
    @{ Path = "HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\Serialize"; Name = "StartupDelayInMSec"; Type = "REG_DWORD"; New = "0" }
)

# ------------------------------------------------------------
# 基础工具函数
# ----------------------------------------------------------

function Write-Log {
    param([string]$Message, [string]$Level = "INFO")
    $line = "[{0}] [{1}] {2}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Level, $Message
    Write-Host $line
    Add-Content -Path $Script:LogFile -Value $line -Encoding UTF8
}

function Show-Header {
    Clear-Host
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host "          Windows 7 全能优化脚本  All-in-One" -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host (" 日志: " + $Script:LogFile) -ForegroundColor DarkGray
    Write-Host ""
}

function Pause-Menu {
    Read-Host "`n按 Enter 返回菜单" | Out-Null
}

function Confirm-Action {
    param([string]$Question)
    $answer = Read-Host ($Question + " [Y/N]")
    if ($answer -match "^(y|Y|yes|Yes|YES|是)$") { return $true }
    return $false
}

function Test-IsAdmin {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Ensure-BackupDir {
    if ($Script:BackupDir -ne $null -and (Test-Path $Script:BackupDir)) { return }
    if (-not (Test-Path $Script:BackupRoot)) {
        New-Item -Path $Script:BackupRoot -ItemType Directory -Force | Out-Null
    }
    $Script:BackupDir = Join-Path $Script:BackupRoot ("Backup_" + $Script:Stamp)
    if (-not (Test-Path $Script:BackupDir)) {
        New-Item -Path $Script:BackupDir -ItemType Directory -Force | Out-Null
    }
}

function Get-LatestBackupDir {
    if (-not (Test-Path $Script:BackupRoot)) { return $null }
    $dirs = @(Get-ChildItem -Path $Script:BackupRoot -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.PSIsContainer -and $_.Name -like "Backup_*" } |
        Sort-Object Name -Descending)
    if ($dirs.Length -gt 0) { return $dirs[0].FullName }
    return $null
}

function New-RestorePoint {
    Write-Log "正在创建系统还原点..."
    try {
        Checkpoint-Computer -Description ("Win7 Optimizer " + $Script:Stamp) -RestorePointType "MODIFY_SETTINGS" -ErrorAction Stop
        Write-Log "系统还原点创建成功。" "OK"
    } catch {
        Write-Log ("创建还原点失败（系统保护可能未开启，或 24 小时内已创建过）: " + $_.Exception.Message) "WARN"
    }
}

# ------------------------------------------------------------
# 诊断报告
# ----------------------------------------------------------

function Get-StartupItems {
    $items = @()
    $idx = 0

    $keys = @(
        @{ Path = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"; Type = "HKCU-Run" },
        @{ Path = "HKCU:\Software\Microsoft\Windows\CurrentVersion\RunOnce"; Type = "HKCU-RunOnce" },
        @{ Path = "HKLM:\Software\Microsoft\Windows\CurrentVersion\Run"; Type = "HKLM-Run" },
        @{ Path = "HKLM:\Software\Microsoft\Windows\CurrentVersion\RunOnce"; Type = "HKLM-RunOnce" },
        @{ Path = "HKLM:\Software\Wow6432Node\Microsoft\Windows\CurrentVersion\Run"; Type = "HKLM32-Run" },
        @{ Path = "HKLM:\Software\Wow6432Node\Microsoft\Windows\CurrentVersion\RunOnce"; Type = "HKLM32-RunOnce" }
    )

    foreach ($k in $keys) {
        if (Test-Path $k.Path) {
            $props = Get-ItemProperty -Path $k.Path -ErrorAction SilentlyContinue
            if ($props -ne $null) {
                foreach ($prop in $props.PSObject.Properties) {
                    if ($prop.Name -notlike "PS*" -and $prop.Name -ne "(default)") {
                        $idx++
                        $items += New-Object PSObject -Property @{
                            Index = $idx
                            Type = "Registry"
                            Location = $k.Path
                            Name = $prop.Name
                            Command = [string]$prop.Value
                            File = ""
                        }
                    }
                }
            }
        }
    }

    $dirs = @()
    $dirs += [Environment]::GetFolderPath("Startup")
    $dirs += (Join-Path $env:ProgramData "Microsoft\Windows\Start Menu\Programs\Startup")

    foreach ($dir in $dirs) {
        if (Test-Path $dir) {
            $files = @(Get-ChildItem -Path $dir -Force -ErrorAction SilentlyContinue | Where-Object { -not $_.PSIsContainer })
            foreach ($f in $files) {
                $idx++
                $items += New-Object PSObject -Property @{
                    Index = $idx
                    Type = "StartupFolder"
                    Location = $dir
                    Name = $f.Name
                    Command = $f.FullName
                    File = $f.FullName
                }
            }
        }
    }

    return $items
}

function Get-TopProcessList {
    param([int]$Count = 20)
    $procs = @(Get-Process | Where-Object { $_.Id -ne $PID } | Sort-Object WorkingSet64 -Descending | Select-Object -First $Count)
    $list = @()
    $idx = 0
    foreach ($p in $procs) {
        $idx++
        $mem = [int]($p.WorkingSet64 / 1MB)
        $cpu = 0
        try { $cpu = [int]$p.TotalProcessorTime.TotalSeconds } catch {}
        $path = ""
        try { $path = $p.Path } catch {}
        $list += New-Object PSObject -Property @{
            Index = $idx
            Name = $p.ProcessName
            Id = $p.Id
            MemoryMB = $mem
            CPUSeconds = $cpu
            Path = $path
        }
    }
    return $list
}

function Get-ReportLines {
    $lines = New-Object System.Collections.ArrayList
    [void]$lines.Add("================ Windows 7 诊断报告 ================")
    [void]$lines.Add("生成时间: " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss"))
    [void]$lines.Add("")

    $os = $null
    $cs = $null
    try { $os = Get-WmiObject Win32_OperatingSystem } catch {}
    try { $cs = Get-WmiObject Win32_ComputerSystem } catch {}

    if ($os -ne $null) {
        [void]$lines.Add("系统: " + $os.Caption + " " + $os.Version)
        [void]$lines.Add("架构: " + $os.OSArchitecture)
        $boot = $os.ConvertToDateTime($os.LastBootUpTime)
        $up = (Get-Date) - $boot
        [void]$lines.Add("上次启动: " + $boot.ToString("yyyy-MM-dd HH:mm:ss") + "（已运行 " + $up.Days + " 天 " + $up.Hours + " 小时）")
        $usedPct = 0
        if ($os.TotalVisibleMemorySize -gt 0) {
            $usedPct = [math]::Round((1 - ($os.FreePhysicalMemory / $os.TotalVisibleMemorySize)) * 100, 1)
        }
        [void]$lines.Add("内存使用率: " + $usedPct + "%")
    }

    if ($cs -ne $null) {
        [void]$lines.Add("计算机: " + $cs.Manufacturer + " " + $cs.Model)
        [void]$lines.Add("总内存: " + [math]::Round($cs.TotalPhysicalMemory / 1GB, 2) + " GB")
    }

    $cpus = @(Get-WmiObject Win32_Processor)
    if ($cpus.Length -gt 0) {
        $cpu = $cpus[0]
        [void]$lines.Add("CPU: " + $cpu.Name + "，核心 " + $cpu.NumberOfCores + " / 线程 " + $cpu.NumberOfLogicalProcessors + "，当前负载 " + $cpu.LoadPercentage + "%")
    }

    $mem = @(Get-WmiObject Win32_PhysicalMemory)
    foreach ($m in $mem) {
        [void]$lines.Add("内存条: " + [math]::Round($m.Capacity / 1GB, 0) + " GB @ " + $m.Speed + " MHz")
    }

    $drives = @(Get-WmiObject Win32_LogicalDisk -Filter "DriveType=3")
    foreach ($d in $drives) {
        if ($d.Size -gt 0) {
            $freePct = [math]::Round(($d.FreeSpace / $d.Size) * 100, 1)
            [void]$lines.Add(("磁盘 {0}: {1} GB 可用 / {2} GB，剩余 {3}%" -f $d.DeviceID, [math]::Round($d.FreeSpace / 1GB, 1), [math]::Round($d.Size / 1GB, 1), $freePct))
            if ($d.DeviceID -eq "C:" -and $freePct -lt 15) {
                [void]$lines.Add("  [警告] C 盘剩余空间低于 15%，会明显影响系统速度。")
            }
        }
    }

    $disks = @(Get-WmiObject Win32_DiskDrive)
    $diskText = ""
    foreach ($d in $disks) {
        if ($d.Size -gt 0) {
            [void]$lines.Add("物理硬盘: " + $d.Model + " / " + [math]::Round($d.Size / 1GB, 0) + " GB")
        }
        $diskText += " " + $d.Model
    }
    if ($diskText -match "SSD|Solid State|NVMe|固态") {
        [void]$lines.Add("硬盘类型: 检测到 SSD 标识（SSD 不需要碎片整理）。")
    } else {
        [void]$lines.Add("硬盘类型: 未检测到 SSD 标识，可能是机械硬盘（可做碎片整理）。")
    }

    $pf = @(Get-WmiObject Win32_PageFileUsage)
    if ($pf.Length -gt 0) {
        foreach ($p in $pf) {
            [void]$lines.Add("页面文件: " + $p.Name + "，已分配 " + $p.AllocatedBaseSize + " MB，当前使用 " + $p.CurrentUsage + " MB")
        }
    } else {
        [void]$lines.Add("页面文件: 未启用或无法查询。")
    }

    $gpus = @(Get-WmiObject Win32_VideoController)
    foreach ($g in $gpus) {
        [void]$lines.Add("显卡: " + $g.Name + "，驱动 " + $g.DriverVersion)
    }

    $power = (& powercfg.exe -getactivescheme) -join " "
    [void]$lines.Add("电源计划: " + $power)

    $av = @()
    try { $av = @(Get-WmiObject -Namespace "root\SecurityCenter2" -Class AntiVirusProduct) } catch {}
    if ($av.Length -gt 0) {
        foreach ($a in $av) {
            [void]$lines.Add("杀毒软件: " + $a.displayName)
        }
    } else {
        [void]$lines.Add("杀毒软件: 未检测到（SecurityCenter2 不可用或未安装）。")
    }

    $defender = Get-Service -Name WinDefend -ErrorAction SilentlyContinue
    if ($defender -ne $null) {
        [void]$lines.Add("Windows Defender 服务: " + $defender.Status)
    }

    [void]$lines.Add("")
    [void]$lines.Add("---- 内存占用最高的 15 个进程 ----")
    $procs = @(Get-Process | Where-Object { $_.Id -ne $PID } | Sort-Object WorkingSet64 -Descending | Select-Object -First 15)
    foreach ($p in $procs) {
        [void]$lines.Add(("{0,-30} PID={1,-8} 内存={2} MB" -f $p.ProcessName, $p.Id, [int]($p.WorkingSet64 / 1MB)))
    }

    [void]$lines.Add("")
    $startup = @(Get-StartupItems)
    [void]$lines.Add("---- 启动项 (" + $startup.Length + ") ----")
    foreach ($s in $startup) {
        $cmd = $s.Command
        if ($cmd.Length -gt 80) { $cmd = $cmd.Substring(0, 80) + "..." }
        [void]$lines.Add(("{0,-18} {1}" -f $s.Name, $cmd))
    }

    $running = @(Get-Service | Where-Object { $_.Status -eq "Running" })
    [void]$lines.Add("")
    [void]$lines.Add("正在运行的服务数量: " + $running.Length)
    [void]$lines.Add("")
    [void]$lines.Add("提示: 机械硬盘和内存不足属于硬件瓶颈，软件优化只能缓解。")
    [void]$lines.Add("====================================================")

    return $lines
}

function Show-DiagnosticReport {
    Show-Header
    $lines = @(Get-ReportLines)
    foreach ($line in $lines) { Write-Host $line }
    $lines | Out-File -FilePath $Script:ReportFile -Encoding UTF8
    Write-Host ""
    Write-Host ("报告已保存到: " + $Script:ReportFile) -ForegroundColor Green
    Pause-Menu
}

# ------------------------------------------------------------
# 清理功能
# ------------------------------------------------------------

function Clear-TempFiles {
    $targets = @(
        $env:TEMP,
        (Join-Path $env:SystemRoot "Temp"),
        (Join-Path $env:LOCALAPPDATA "Temp")
    )
    $removed = 0
    foreach ($path in $targets) {
        if ($path -ne $null -and (Test-Path $path)) {
            Get-ChildItem -Path $path -Force -ErrorAction SilentlyContinue | ForEach-Object {
                try {
                    Remove-Item -Path $_.FullName -Recurse -Force -ErrorAction Stop
                    $removed++
                } catch {}
            }
        }
    }
    Write-Log ("临时文件清理完成，已删除 " + $removed + " 个项目。") "OK"
}

function Clear-ThumbnailCache {
    $explorerDir = Join-Path $env:LOCALAPPDATA "Microsoft\Windows\Explorer"
    if (Test-Path $explorerDir) {
        Get-ChildItem -Path $explorerDir -Filter "thumbcache_*.db" -Force -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
    }
    $iconCache = Join-Path $env:LOCALAPPDATA "IconCache.db"
    if (Test-Path $iconCache) {
        Remove-Item -Path $iconCache -Force -ErrorAction SilentlyContinue
    }
    Write-Log "缩略图/图标缓存已尝试清理（被占用的文件会跳过，重启后自动重建）。" "OK"
}

function Clear-RecycleBinSafe {
    $rb = Join-Path $env:SystemDrive '$Recycle.Bin'
    if (Test-Path $rb) {
        $sidDirs = @(Get-ChildItem -Path $rb -Force -ErrorAction SilentlyContinue | Where-Object { $_.PSIsContainer })
        foreach ($sid in $sidDirs) {
            Get-ChildItem -Path $sid.FullName -Force -ErrorAction SilentlyContinue | ForEach-Object {
                Remove-Item -Path $_.FullName -Recurse -Force -ErrorAction SilentlyContinue
            }
        }
    }
    Write-Log "回收站内容已清空。" "OK"
}

function Clear-UpdateCache {
    $wasRunning = $false
    try {
        $svc = Get-Service -Name wuauserv -ErrorAction Stop
        if ($svc.Status -eq "Running") {
            $wasRunning = $true
            Stop-Service -Name wuauserv -Force -ErrorAction Stop
        }
    } catch {}

    $download = Join-Path $env:SystemRoot "SoftwareDistribution\Download"
    if (Test-Path $download) {
        Get-ChildItem -Path $download -Force -ErrorAction SilentlyContinue | ForEach-Object {
            Remove-Item -Path $_.FullName -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    if ($wasRunning) {
        Start-Service -Name wuauserv -ErrorAction SilentlyContinue
    }
    Write-Log "Windows Update 下载缓存清理完成（不影响已安装更新）。" "OK"
}

function Clear-BrowserCache {
    $paths = @()

    $chromeRoot = Join-Path $env:LOCALAPPDATA "Google\Chrome\User Data"
    if (Test-Path $chromeRoot) {
        $profiles = @(Get-ChildItem -Path $chromeRoot -Force -ErrorAction SilentlyContinue | Where-Object { $_.PSIsContainer })
        foreach ($p in $profiles) {
            $paths += (Join-Path $p.FullName "Cache")
            $paths += (Join-Path $p.FullName "Code Cache")
            $paths += (Join-Path $p.FullName "GPUCache")
        }
    }

    $edgeRoot = Join-Path $env:LOCALAPPDATA "Microsoft\Edge\User Data"
    if (Test-Path $edgeRoot) {
        $profiles = @(Get-ChildItem -Path $edgeRoot -Force -ErrorAction SilentlyContinue | Where-Object { $_.PSIsContainer })
        foreach ($p in $profiles) {
            $paths += (Join-Path $p.FullName "Cache")
            $paths += (Join-Path $p.FullName "Code Cache")
            $paths += (Join-Path $p.FullName "GPUCache")
        }
    }

    $ffRoot = Join-Path $env:APPDATA "Mozilla\Firefox\Profiles"
    if (Test-Path $ffRoot) {
        $profiles = @(Get-ChildItem -Path $ffRoot -Force -ErrorAction SilentlyContinue | Where-Object { $_.PSIsContainer })
        foreach ($p in $profiles) {
            $paths += (Join-Path $p.FullName "cache2")
            $paths += (Join-Path $p.FullName "startupCache")
        }
    }

    $paths += (Join-Path $env:LOCALAPPDATA "Microsoft\Windows\Temporary Internet Files")

    $count = 0
    foreach ($path in $paths) {
        if (Test-Path $path) {
            Get-ChildItem -Path $path -Force -ErrorAction SilentlyContinue | ForEach-Object {
                try {
                    Remove-Item -Path $_.FullName -Recurse -Force -ErrorAction Stop
                    $count++
                } catch {}
            }
        }
    }
    Write-Log ("浏览器缓存清理完成，已删除 " + $count + " 个项目（不会删除密码和收藏）。") "OK"
}

# ------------------------------------------------------------
# 性能设置与备份
# ------------------------------------------------------------

function Save-VisualBackup {
    Ensure-BackupDir
    $file = Join-Path $Script:BackupRoot "visual_values.txt"
    if (Test-Path $file) {
        Write-Log "已存在原始视觉设置备份，跳过重复备份。" "INFO"
        return
    }
    $lines = @()

    foreach ($s in $Script:VisualSettings) {
        $psPath = "HKCU:\" + $s.Path.Substring(5)
        $prop = $null
        try {
            $item = Get-ItemProperty -Path $psPath -Name $s.Name -ErrorAction Stop
            $matched = @($item.PSObject.Properties.Match($s.Name))
            if ($matched.Length -gt 0) { $prop = $matched[0] }
        } catch {}
        if ($prop -ne $null) { $v = [string]$prop.Value } else { $v = "<absent>" }
        $lines += ($s.Path + "|" + $s.Name + "|" + $s.Type + "|" + $v)
    }

    $lines | Out-File -FilePath $file -Encoding UTF8
    Write-Log "已备份原始视觉效果和启动延迟设置。" "OK"
}

function Save-PowerPlan {
    Ensure-BackupDir
    $file = Join-Path $Script:BackupRoot "power_plan.txt"
    if (Test-Path $file) {
        Write-Log "已存在电源计划备份，跳过重复备份。" "INFO"
        return
    }
    $active = (& powercfg.exe -getactivescheme) -join " "
    $active | Out-File -FilePath $file -Encoding UTF8
    Write-Log "已备份当前电源计划。" "OK"
}

function Set-PerformanceTweaks {
    Ensure-BackupDir
    if (-not $Script:TweaksBackedUp) {
        Save-VisualBackup
        Save-PowerPlan
        $Script:TweaksBackedUp = $true
    }

    foreach ($s in $Script:VisualSettings) {
        & reg.exe add $s.Path /v $s.Name /t $s.Type /d $s.New /f | Out-Null
    }

    & powercfg.exe -setactive $Script:HighPerformanceGuid | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Log "高性能电源计划设置失败（可能被 OEM 移除），其他设置不受影响。" "WARN"
    } else {
        Write-Log "已切换到高性能电源计划。" "OK"
    }

    Write-Log "已应用最佳性能视觉效果、关闭启动延迟。" "OK"
    Write-Log "部分设置需要重启或重新登录后完全生效。" "INFO"
}

# ------------------------------------------------------------
# 进程专项优化
# ------------------------------------------------------------

function Invoke-ProcessOptimize {
    Show-Header
    Write-Host "按内存占用排序的进程如下。CPU时间为进程启动以来的累计值，仅供参考。" -ForegroundColor DarkGray
    Write-Host ""

    $list = @(Get-TopProcessList 25)
    if ($list.Length -eq 0) {
        Write-Host "没有可显示的进程。"
        Pause-Menu
        return
    }

    $list | Select-Object @{ Name = "序号"; Expression = { $_.Index } }, @{ Name = "进程"; Expression = { $_.Name } }, @{ Name = "PID"; Expression = { $_.Id } }, @{ Name = "内存(MB)"; Expression = { $_.MemoryMB } }, @{ Name = "CPU时间(秒)"; Expression = { $_.CPUSeconds } } | Format-Table -AutoSize

    Write-Host "提示：结束进程可能导致未保存的数据丢失，脚本不会自动结束任何进程。" -ForegroundColor Yellow
    Write-Host ""

    $selection = Read-Host "输入要结束的序号（逗号分隔，直接回车跳过）"
    if ($selection.Trim() -eq "") { return }

    $critical = @("system", "idle", "smss", "csrss", "wininit", "winlogon", "services", "lsass", "svchost", "explorer", "dwm", "audiodg", "spoolsv", "taskhost", "conhost", "msmpeng", "nissrv", "searchindexer", "wscsvc", "sppsvc", "trustedinstaller", "lsm")
    $indexes = @()
    foreach ($part in ($selection -split "[,，\s]+")) {
        if ($part -ne "") {
            try { $indexes += [int]$part } catch {}
        }
    }

    $valid = @()
    foreach ($idx in $indexes) {
        if ($idx -lt 1 -or $idx -gt $list.Length) {
            Write-Host ("忽略无效序号: " + $idx) -ForegroundColor Yellow
            continue
        }
        $item = $list[$idx - 1]
        if ($critical -contains $item.Name.ToLower()) {
            Write-Host ("跳过系统关键进程: " + $item.Name) -ForegroundColor Yellow
            continue
        }
        $valid += $item
    }

    if ($valid.Length -eq 0) {
        Write-Host "没有可结束的进程。"
        Pause-Menu
        return
    }

    if (-not (Confirm-Action ("确认结束选中的 " + $valid.Length + " 个进程？未保存的数据可能丢失"))) {
        Pause-Menu
        return
    }

    foreach ($item in $valid) {
        try {
            Stop-Process -Id $item.Id -Force -ErrorAction Stop
            Write-Log ("已结束进程: " + $item.Name + " (PID " + $item.Id + ")") "OK"
        } catch {
            Write-Log ("结束进程失败: " + $item.Name + " - " + $_.Exception.Message) "WARN"
        }
    }

    Pause-Menu
}

# ------------------------------------------------------------
# 启动项管理
# ------------------------------------------------------------

function Invoke-StartupManager {
    Show-Header
    $items = @(Get-StartupItems)

    if ($items.Length -eq 0) {
        Write-Host "未发现启动项。"
        Pause-Menu
        return
    }

    $items | Select-Object @{ Name = "序号"; Expression = { $_.Index } }, @{ Name = "类型"; Expression = { $_.Type } }, @{ Name = "名称"; Expression = { $_.Name } }, @{ Name = "命令"; Expression = { if ($_.Command.Length -gt 60) { $_.Command.Substring(0, 60) + "..." } else { $_.Command } } } | Format-Table -AutoSize

    Write-Host "1. 禁用启动项"
    Write-Host "2. 恢复上一次备份的启动项"
    Write-Host "0. 返回"
    $choice = Read-Host "请选择"

    if ($choice -eq "2") {
        if (Confirm-Action "从备份恢复启动项？") {
            Restore-StartupBackup
            Write-Host "启动项恢复完成。"
        }
        Pause-Menu
        return
    }

    if ($choice -ne "1") { return }

    $selection = Read-Host "输入要禁用的序号（逗号分隔，直接回车取消）"
    if ($selection.Trim() -eq "") { return }

    $indexes = @()
    foreach ($part in ($selection -split "[,，\s]+")) {
        if ($part -ne "") {
            try { $indexes += [int]$part } catch {}
        }
    }

    Ensure-BackupDir
    $manifest = Join-Path $Script:BackupDir "startup_manifest.txt"

    foreach ($idx in $indexes) {
        if ($idx -lt 1 -or $idx -gt $items.Length) {
            Write-Host ("忽略无效序号: " + $idx) -ForegroundColor Yellow
            continue
        }
        $item = $items[$idx - 1]
        try {
            if ($item.Type -eq "Registry") {
                $regPath = $item.Location.Replace("HKCU:\", "HKCU\").Replace("HKLM:\", "HKLM\")
                $backupFile = Join-Path $Script:BackupDir ("startup_" + $item.Index + ".reg")
                & reg.exe export $regPath $backupFile /y | Out-Null
                Remove-ItemProperty -Path $item.Location -Name $item.Name -ErrorAction Stop
                Add-Content -Path $manifest -Value ("Registry|" + $item.Location + "|" + $item.Name + "|" + $backupFile) -Encoding UTF8
                Write-Log ("已禁用启动项(注册表): " + $item.Name) "OK"
            } else {
                $dest = Join-Path $Script:BackupDir ("Startup_" + $item.Index + "_" + $item.Name)
                Move-Item -Path $item.File -Destination $dest -Force -ErrorAction Stop
                Add-Content -Path $manifest -Value ("Folder|" + $item.File + "|" + $dest) -Encoding UTF8
                Write-Log ("已禁用启动项(启动文件夹): " + $item.Name) "OK"
            }
        } catch {
            Write-Log ("禁用启动项失败: " + $item.Name + " - " + $_.Exception.Message) "WARN"
        }
    }

    Write-Host "操作完成，重启后生效。"
    Pause-Menu
}

function Restore-StartupBackup {
    if (-not (Test-Path $Script:BackupRoot)) { return }

    $dirs = @(Get-ChildItem -Path $Script:BackupRoot -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.PSIsContainer -and $_.Name -like "Backup_*" } |
        Sort-Object Name)

    foreach ($dir in $dirs) {
        $manifest = Join-Path $dir.FullName "startup_manifest.txt"
        if (-not (Test-Path $manifest)) { continue }

        $lines = Get-Content -Path $manifest -Encoding UTF8
        foreach ($line in $lines) {
            $parts = $line -split "\|"
            if ($parts.Length -eq 4 -and $parts[0] -eq "Registry") {
                $backupFile = $parts[3]
                if (Test-Path $backupFile) {
                    & reg.exe import $backupFile | Out-Null
                    Write-Log ("已恢复注册表启动项: " + $parts[2]) "OK"
                }
            } elseif ($parts.Length -eq 3 -and $parts[0] -eq "Folder") {
                $original = $parts[1]
                $backupFile = $parts[2]
                if ((Test-Path $backupFile) -and (Test-Path (Split-Path -Parent $original))) {
                    Move-Item -Path $backupFile -Destination $original -Force -ErrorAction SilentlyContinue
                    Write-Log ("已恢复启动文件夹项: " + (Split-Path -Leaf $original)) "OK"
                }
            }
        }
    }
}

# ------------------------------------------------------------
# 服务专项优化
# ------------------------------------------------------------

function Get-ServiceCatalog {
    return @(
        @{ Name = "RemoteRegistry"; Impact = "低"; Note = "禁止远程修改注册表，家用可禁用" },
        @{ Name = "TermService"; Impact = "中"; Note = "不用远程桌面可禁用" },
        @{ Name = "TabletInputService"; Impact = "低"; Note = "无触摸屏或手写笔可禁用" },
        @{ Name = "WSearch"; Impact = "中"; Note = "禁用后开始菜单搜索和文件索引失效" },
        @{ Name = "WMPNetworkSvc"; Impact = "低"; Note = "不共享媒体库可禁用" },
        @{ Name = "HomeGroupListener"; Impact = "低"; Note = "不用家庭组可禁用" },
        @{ Name = "HomeGroupProvider"; Impact = "低"; Note = "不用家庭组可禁用" },
        @{ Name = "Fax"; Impact = "低"; Note = "不用传真可禁用" },
        @{ Name = "bthserv"; Impact = "中"; Note = "不用蓝牙可禁用" },
        @{ Name = "WerSvc"; Impact = "低"; Note = "Windows 错误报告，禁用不影响正常使用" },
        @{ Name = "ShellHWDetection"; Impact = "低"; Note = "禁用后可能影响自动播放和设备识别" },
        @{ Name = "SCardSvr"; Impact = "低"; Note = "不用智能卡可禁用" },
        @{ Name = "seclogon"; Impact = "低"; Note = "不用'以其他用户身份运行'可禁用" },
        @{ Name = "WPDBusEnum"; Impact = "低"; Note = "不接便携设备可禁用" },
        @{ Name = "Spooler"; Impact = "中"; Note = "不打印可禁用" },
        @{ Name = "SysMain"; Impact = "中"; Note = "机械硬盘建议保留；SSD 可禁用" },
        @{ Name = "Themes"; Impact = "中"; Note = "禁用后变为经典主题，界面变丑" },
        @{ Name = "PcaSvc"; Impact = "低"; Note = "程序兼容性助手，可禁用" }
    )
}

function Add-ServiceBackup {
    param([string]$Name, [string]$Mode)
    Ensure-BackupDir
    $file = Join-Path $Script:BackupRoot "services.txt"
    if (Test-Path $file) {
        $existing = Get-Content -Path $file -Encoding UTF8
        foreach ($line in $existing) {
            if (($line -split "\|")[0] -eq $Name) { return }
        }
    }
    Add-Content -Path $file -Value ($Name + "|" + $Mode) -Encoding UTF8
}

function Invoke-ServiceOptimizer {
    Show-Header
    Write-Host "以下是非系统核心服务。禁用前请阅读说明，脚本不会自动选择任何服务。" -ForegroundColor Yellow
    Write-Host ""

    $catalog = Get-ServiceCatalog
    $rows = @()
    $idx = 0

    foreach ($s in $catalog) {
        $wmi = Get-WmiObject Win32_Service -Filter ("Name='" + $s.Name + "'")
        if ($wmi -ne $null) {
            $idx++
            $rows += New-Object PSObject -Property @{
                Index = $idx
                Name = $wmi.Name
                Display = $wmi.DisplayName
                State = $wmi.State
                StartMode = $wmi.StartMode
                Impact = $s.Impact
                Note = $s.Note
            }
        }
    }

    if ($rows.Length -eq 0) {
        Write-Host "没有找到可管理的服务。"
        Pause-Menu
        return
    }

    $rows | Select-Object @{ Name = "序号"; Expression = { $_.Index } }, @{ Name = "服务名"; Expression = { $_.Name } }, @{ Name = "状态"; Expression = { $_.State } }, @{ Name = "启动类型"; Expression = { $_.StartMode } }, @{ Name = "影响"; Expression = { $_.Impact } }, @{ Name = "说明"; Expression = { $_.Note } } | Format-Table -AutoSize

    $selection = Read-Host "输入要优化的序号（逗号分隔，直接回车返回）"
    if ($selection.Trim() -eq "") { return }

    $indexes = @()
    foreach ($part in ($selection -split "[,，\s]+")) {
        if ($part -ne "") {
            try { $indexes += [int]$part } catch {}
        }
    }

    Write-Host ""
    Write-Host "1. 设为手动（推荐，仍可按需启动）"
    Write-Host "2. 设为禁用（更彻底，可能影响相关功能）"
    $modeChoice = Read-Host "选择启动类型"

    $target = "demand"
    $targetLabel = "Manual"
    if ($modeChoice -eq "2") {
        $target = "disabled"
        $targetLabel = "Disabled"
    }

    $stopNow = Confirm-Action "是否同时停止选中的服务？（推荐，立即释放资源）"

    foreach ($idx in $indexes) {
        if ($idx -lt 1 -or $idx -gt $rows.Length) {
            Write-Host ("忽略无效序号: " + $idx) -ForegroundColor Yellow
            continue
        }
        $row = $rows[$idx - 1]
        $svcObj = Get-Service -Name $row.Name -ErrorAction SilentlyContinue
        if ($svcObj -eq $null) { continue }

        $deps = @($svcObj.DependentServices | Where-Object { $_.Status -eq "Running" })
        if ($deps.Length -gt 0) {
            $depNames = ($deps | ForEach-Object { $_.Name }) -join ", "
            Write-Host ("跳过 " + $row.Name + "：有正在运行的依赖服务 " + $depNames) -ForegroundColor Yellow
            continue
        }

        Add-ServiceBackup $row.Name $row.StartMode

        & sc.exe config $row.Name start= $target 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Write-Log ("服务 " + $row.Name + " 启动类型已设为 " + $targetLabel) "OK"
            if ($stopNow -and $row.State -eq "Running") {
                & sc.exe stop $row.Name 2>&1 | Out-Null
                Write-Log ("已尝试停止服务 " + $row.Name) "INFO"
            }
        } else {
            Write-Log ("设置服务失败（可能受保护或权限不足）: " + $row.Name) "WARN"
        }
    }

    Write-Host "操作完成。建议重启电脑后观察效果。"
    Pause-Menu
}

function Restore-ServiceBackup {
    $file = Join-Path $Script:BackupRoot "services.txt"
    if (-not (Test-Path $file)) { return }

    $lines = Get-Content -Path $file -Encoding UTF8
    foreach ($line in $lines) {
        $parts = $line -split "\|"
        if ($parts.Length -eq 2) {
            $name = $parts[0]
            $mode = $parts[1]
            $scMode = "demand"
            if ($mode -eq "Auto") { $scMode = "auto" }
            elseif ($mode -eq "Disabled") { $scMode = "disabled" }

            & sc.exe config $name start= $scMode 2>&1 | Out-Null
            Write-Log ("已恢复服务启动类型: " + $name + " -> " + $mode) "OK"
            if ($mode -eq "Auto") {
                & sc.exe start $name 2>&1 | Out-Null
            }
        }
    }
}

# ------------------------------------------------------------
# 视觉/电源恢复
# ------------------------------------------------------------

function Restore-VisualSettings {
    $file = Join-Path $Script:BackupRoot "visual_values.txt"
    if (-not (Test-Path $file)) { return }

    $lines = Get-Content -Path $file -Encoding UTF8
    foreach ($line in $lines) {
        $parts = $line -split "\|"
        if ($parts.Length -eq 4) {
            $path = $parts[0]
            $name = $parts[1]
            $type = $parts[2]
            $value = $parts[3]
            if ($value -eq "<absent>") {
                & reg.exe delete $path /v $name /f 2>&1 | Out-Null
            } else {
                & reg.exe add $path /v $name /t $type /d $value /f | Out-Null
            }
        }
    }
    Write-Log "视觉效果和启动延迟设置已恢复。" "OK"
}

function Restore-PowerPlan {
    $file = Join-Path $Script:BackupRoot "power_plan.txt"
    if (-not (Test-Path $file)) { return }

    $line = (Get-Content -Path $file -Encoding UTF8 | Select-Object -First 1)
    if ($line -match "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})") {
        & powercfg.exe -setactive $matches[1] | Out-Null
        Write-Log "电源计划已恢复。" "OK"
    }
}

function Invoke-Restore {
    Show-Header
    $dir = Get-LatestBackupDir
    $hasRootBackup = ((Test-Path (Join-Path $Script:BackupRoot "visual_values.txt")) -or
        (Test-Path (Join-Path $Script:BackupRoot "power_plan.txt")) -or
        (Test-Path (Join-Path $Script:BackupRoot "services.txt")))

    if ($dir -eq $null -and -not $hasRootBackup) {
        Write-Host "没有找到备份目录。请先执行一次优化（一键优化或启动项/服务优化）。"
        Pause-Menu
        return
    }

    if ($dir -ne $null) { Write-Host ("最新备份目录: " + $dir) }
    Write-Host ("备份根目录: " + $Script:BackupRoot)
    if (-not (Confirm-Action "恢复启动项、服务启动类型、视觉效果和电源计划？")) { return }

    Restore-ServiceBackup
    Restore-StartupBackup
    Restore-VisualSettings
    Restore-PowerPlan
    Write-Log "恢复完成，建议重启电脑。" "OK"
    Write-Host "恢复完成，建议重启电脑。"
    Pause-Menu
}

# ------------------------------------------------------------
# 磁盘工具
# ------------------------------------------------------------

function Invoke-DiskTools {
    $choice = ""
    while ($choice -ne "0") {
        Show-Header

        $c = Get-WmiObject Win32_LogicalDisk -Filter "DeviceID='C:'"
        if ($c -ne $null -and $c.Size -gt 0) {
            $pct = [math]::Round(($c.FreeSpace / $c.Size) * 100, 1)
            Write-Host ("C 盘: " + [math]::Round($c.FreeSpace / 1GB, 1) + " GB 可用 / " + [math]::Round($c.Size / 1GB, 1) + " GB（剩余 " + $pct + "%）") -ForegroundColor Green
            if ($pct -lt 15) {
                Write-Host "警告: C 盘可用空间低于 15%，会明显影响系统速度。" -ForegroundColor Red
            }
        }

        $diskModels = ""
        try {
            $diskModels = ((Get-WmiObject Win32_DiskDrive | ForEach-Object { $_.Model }) -join " ")
        } catch {}
        $likelySSD = $diskModels -match "SSD|Solid State|NVMe|固态"
        if ($likelySSD) {
            Write-Host "检测到 SSD 型号标识：通常不需要碎片整理。" -ForegroundColor DarkGray
        } else {
            Write-Host "未检测到 SSD 标识：系统盘可能是机械硬盘，可考虑碎片整理。" -ForegroundColor DarkGray
        }
        Write-Host ""

        Write-Host " 1. 清理临时文件"
        Write-Host " 2. 清理浏览器缓存（Chrome/Edge/Firefox/IE）"
        Write-Host " 3. 清理 Windows Update 下载缓存"
        Write-Host " 4. 清空回收站"
        Write-Host " 5. 清理缩略图/图标缓存"
        Write-Host " 6. 关闭休眠，删除 hiberfil.sys"
        Write-Host " 7. 分析 C 盘碎片（只读）"
        Write-Host " 8. 整理 C 盘（机械硬盘，可能耗时很久）"
        Write-Host " 9. 安排下次开机检查 C 盘"
        Write-Host "10. 系统文件检查 sfc /scannow"
        Write-Host " 0. 返回"
        Write-Host ""

        $choice = Read-Host "请选择"

        switch ($choice) {
            "1" {
                if (Confirm-Action "清理临时文件？") { Clear-TempFiles }
                Pause-Menu
            }
            "2" {
                if (Confirm-Action "请先关闭所有浏览器。清理浏览器缓存（不会删除密码和收藏）？") { Clear-BrowserCache }
                Pause-Menu
            }
            "3" {
                if (Confirm-Action "清理 Windows Update 下载缓存？不影响已安装更新") { Clear-UpdateCache }
                Pause-Menu
            }
            "4" {
                if (Confirm-Action "清空回收站？删除后不可恢复") { Clear-RecycleBinSafe }
                Pause-Menu
            }
            "5" {
                if (Confirm-Action "清理缩略图和图标缓存？资源管理器会自动重建") { Clear-ThumbnailCache }
                Pause-Menu
            }
            "6" {
                $hiber = Join-Path $env:SystemDrive "hiberfil.sys"
                if (Test-Path $hiber) {
                    if (Confirm-Action "关闭休眠并删除 hiberfil.sys？笔记本将无法使用休眠") {
                        & powercfg.exe -h off | Out-Null
                        Write-Log "休眠已关闭，hiberfil.sys 已释放。" "OK"
                    }
                } else {
                    Write-Host "未发现 hiberfil.sys，休眠可能已经关闭。"
                }
                Pause-Menu
            }
            "7" {
                Write-Host "正在分析 C 盘碎片（只读，不会修改数据）..."
                & defrag.exe C: /A
                Pause-Menu
            }
            "8" {
                if ($likelySSD) {
                    Write-Host "检测到 SSD，通常不需要碎片整理；SSD 上频繁整理没有收益。" -ForegroundColor Yellow
                }
                if (Confirm-Action "整理 C 盘可能耗时很久，期间电脑会变慢，继续？") {
                    & defrag.exe C: /V
                }
                Pause-Menu
            }
            "9" {
                if (Confirm-Action "安排在下次开机检查 C 盘？可能耗时很久") {
                    & fsutil.exe dirty set C:
                    Write-Host "已标记，下次开机时会运行 chkdsk 检查 C 盘。"
                }
                Pause-Menu
            }
            "10" {
                if (Confirm-Action "运行系统文件检查 sfc /scannow？可能耗时 10-30 分钟") {
                    & sfc.exe /scannow
                }
                Pause-Menu
            }
            "0" {}
            default {
                Write-Host "无效选择。" -ForegroundColor Yellow
                Start-Sleep -Seconds 1
            }
        }
    }
}

# ------------------------------------------------------------
# 一键安全优化
# ------------------------------------------------------------

function Invoke-SafeOptimize {
    Show-Header
    Write-Host "将执行以下安全操作（均可通过菜单 7 恢复）：" -ForegroundColor Cyan
    Write-Host "  - 创建系统还原点（失败会提示，不中断）"
    Write-Host "  - 清理临时文件和缩略图缓存"
    Write-Host "  - 设置最佳性能视觉效果"
    Write-Host "  - 切换高性能电源计划"
    Write-Host "  - 关闭启动延迟"
    Write-Host "  - 刷新 DNS 缓存"
    Write-Host ""
    Write-Host "不会自动结束进程、不会自动禁用服务、不会删除个人文件。" -ForegroundColor Yellow
    Write-Host ""

    if (-not (Confirm-Action "继续执行一键安全优化？")) { return }

    New-RestorePoint
    Clear-TempFiles
    Clear-ThumbnailCache
    Set-PerformanceTweaks
    & ipconfig.exe /flushdns | Out-Null
    Write-Log "DNS 缓存已刷新。" "OK"

    $lines = @(Get-ReportLines)
    $lines | Out-File -FilePath $Script:ReportFile -Encoding UTF8
    Write-Log ("优化后诊断报告已保存: " + $Script:ReportFile) "OK"

    Write-Host ""
    Write-Host "一键安全优化完成。请重启或重新登录，让视觉效果和启动延迟设置完全生效。" -ForegroundColor Green
    Write-Host ("诊断报告: " + $Script:ReportFile)

    Pause-Menu
}

# ------------------------------------------------------------
# 主菜单与启动
# ------------------------------------------------------------

Write-Log "===== Win7 All-in-One Optimizer 启动 =====" "INFO"
Write-Log ("PowerShell 版本: " + $PSVersionTable.PSVersion.ToString()) "INFO"

if (-not (Test-IsAdmin)) {
    Write-Host "需要管理员权限。请右键 .bat 文件，选择'以管理员身份运行'。" -ForegroundColor Red
    Read-Host "按 Enter 退出" | Out-Null
    exit
}

$choice = ""
while ($choice -ne "0") {
    Show-Header
    Write-Host " 1. 生成完整诊断报告（只读，不改系统）"
    Write-Host " 2. 一键安全优化（还原点 + 清理 + 视觉/电源/启动延迟）"
    Write-Host " 3. 进程专项优化（按内存排序，手动结束进程）"
    Write-Host " 4. 启动项管理（禁用/恢复开机自启）"
    Write-Host " 5. 服务专项优化（按需禁用非必要服务）"
    Write-Host " 6. 磁盘清理与检查"
    Write-Host " 7. 恢复上一次优化（启动项/服务/视觉/电源）"
    Write-Host " 0. 退出"
    Write-Host ""
    Write-Host "提示：3/4/5 为手动选择型优化，脚本不会擅自结束进程或禁用服务。" -ForegroundColor DarkGray
    Write-Host ""

    $choice = Read-Host "请输入序号"

    switch ($choice) {
        "1" { Show-DiagnosticReport }
        "2" { Invoke-SafeOptimize }
        "3" { Invoke-ProcessOptimize }
        "4" { Invoke-StartupManager }
        "5" { Invoke-ServiceOptimizer }
        "6" { Invoke-DiskTools }
        "7" { Invoke-Restore }
        "0" { Write-Log "用户退出。" "INFO" }
        default {
            Write-Host "无效选择，请重新输入。" -ForegroundColor Yellow
            Start-Sleep -Seconds 1
        }
    }
}

Write-Host "已退出。按 Enter 关闭窗口。" -ForegroundColor Green
Read-Host | Out-Null
