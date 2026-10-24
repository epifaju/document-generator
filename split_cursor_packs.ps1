<#
.SYNOPSIS
  Découpe les packs Markdown (cursor_agents_adgendoc.md, cursor_rules_adgendoc.md)
  en fichiers individuels sous .cursor/agents/ et .cursor/rules/.

.DESCRIPTION
  Pour chaque titre "## `.cursor/<dossier>/<fichier>`" suivi d'un bloc ```markdown,
  écrit le contenu du bloc dans le chemin indiqué (UTF-8 sans BOM).
  - Seuls les chemins commençant par ".cursor/" sont écrits (jamais AGENTS.md).
  - Un fichier existant n'est PAS écrasé, sauf avec -Force.
  - -DryRun affiche ce qui serait fait sans rien écrire.

.EXAMPLE
  # Depuis la racine du projet. Un seul argument -Pack, chemins separes par une virgule SANS espace :
  powershell -NoProfile -ExecutionPolicy Bypass -File .\split_cursor_packs.ps1 `
    -Pack ".\docs\packs\cursor_agents_adgendoc.md,.\docs\packs\cursor_rules_adgendoc.md" -DryRun
#>
[CmdletBinding(PositionalBinding = $false)]
param(
  [Parameter(Mandatory = $true, Position = 0)][string[]]$Pack,
  [string]$ProjectRoot = (Get-Location).Path,
  [switch]$Force,
  [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$pattern = '(?ms)^##\s+`(\.cursor/[^`]+)`[ \t]*\r?\n[ \t]*\r?\n?```markdown\r?\n(.*?)\r?\n```'
$created = 0; $skipped = 0

# Accepte plusieurs chemins (separes par des espaces ou des virgules)
$packFiles = @($Pack | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })

foreach ($file in $packFiles) {
  if (-not (Test-Path -LiteralPath $file)) { throw "Pack introuvable : $file" }
  $text = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $file).Path, $utf8NoBom)
  $matches = [regex]::Matches($text, $pattern)
  Write-Host "$file : $($matches.Count) bloc(s) trouve(s)"

  foreach ($m in $matches) {
    $relative = $m.Groups[1].Value
    $content = $m.Groups[2].Value
    if ($relative -notmatch '^\.cursor/(agents|rules)/[A-Za-z0-9._-]+\.(md|mdc)$') {
      Write-Host "  IGNORE (chemin non autorise) : $relative"; $skipped++; continue
    }
    if (-not $content.StartsWith('---')) {
      Write-Host "  IGNORE (en-tete YAML absent) : $relative"; $skipped++; continue
    }
    $target = Join-Path $ProjectRoot ($relative -replace '/', [IO.Path]::DirectorySeparatorChar)
    if ((Test-Path -LiteralPath $target) -and -not $Force) {
      Write-Host "  EXISTE (non ecrase) : $relative"; $skipped++; continue
    }
    if ($DryRun) {
      Write-Host "  [DRY-RUN] ecrirait : $relative"; continue
    }
    $dir = Split-Path -Parent $target
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    [System.IO.File]::WriteAllText($target, $content + "`n", $utf8NoBom)
    Write-Host "  ECRIT : $relative"; $created++
  }
}
Write-Host "Termine. ecrits=$created ignores/existants=$skipped"
