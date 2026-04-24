param(
    [string]$Message = ""
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$branch = (git branch --show-current).Trim()
if ([string]::IsNullOrWhiteSpace($branch)) {
    throw "无法识别当前分支。"
}

if ([string]::IsNullOrWhiteSpace($Message)) {
    $Message = "chore: 自动发布 $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
}

Write-Host "开始本地校验与打包..."
& .\gradlew.bat :app:testDebugUnitTest :app:assembleRelease
if ($LASTEXITCODE -ne 0) {
    throw "Gradle 构建失败，已停止推送。"
}

Write-Host "开始整理 Git 变更..."
git add -A -- ":!/.vscode/settings.json"
if ($LASTEXITCODE -ne 0) {
    throw "Git 暂存失败。"
}

git diff --cached --quiet
if ($LASTEXITCODE -ne 0) {
    Write-Host "检测到已暂存变更，开始提交..."
    git commit -m $Message
    if ($LASTEXITCODE -ne 0) {
        throw "Git 提交失败。"
    }
} else {
    Write-Host "没有新的已暂存变更，跳过提交。"
}

Write-Host "推送到 GitHub 分支 $branch ..."
git push origin $branch
if ($LASTEXITCODE -ne 0) {
    throw "Git 推送失败。"
}

Write-Host "推送完成。GitHub Actions 会自动构建并发布新的预发布 Release。"
