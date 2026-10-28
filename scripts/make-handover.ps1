<#
.SYNOPSIS
    生成包含正式签名和现有更新 token 的私密发布交接包。
.DESCRIPTION
    从当前构建机读取白名单内四个 WHALE_* 发布配置，不复制 SDK 路径、
    AI Key、原始 local.properties、SSH 密码或所有者 SSH 私钥。
    产物仅通过私密渠道交给发布者，不能上传到 GitHub Release。
.EXAMPLE
    .\scripts\make-handover.ps1
    .\scripts\make-handover.ps1 -ZipPath "D:\private\publishing.zip"
#>
param(
    [string]$SigningDir = "",
    [string]$ZipPath = "",
    [string]$Python = "python",
    [switch]$IncludeApk
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if ($SigningDir -eq "") { $SigningDir = $root }
if ($ZipPath -eq "") {
    $ZipPath = Join-Path $root (".toolchain\private-handover\publishing-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".zip")
}
$arguments = @((Join-Path $PSScriptRoot "publishing-config.py"), "export", "--source", $SigningDir, "--output", $ZipPath)
if ($IncludeApk) { $arguments += "--include-apk" }
& $Python @arguments
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "只私密交付这个 ZIP。服务器 SSH 密码请另行交接，不上传 Git 或 Release。"
Write-Host "接收者先 git pull，再执行：python scripts/publishing-config.py import --bundle <ZIP完整路径>"
Write-Host "随后执行：python scripts/deploy-update.py --check"
