param(
    [Parameter(Mandatory)][ValidatePattern('^[a-z][a-z0-9-]{4,28}[a-z0-9]$')][string]$ProjectId,
    [Parameter(Mandatory)][ValidatePattern('^[a-z]+-[a-z]+[0-9]$')][string]$Region,
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]+@[a-z0-9-]+\.iam\.gserviceaccount\.com$')][string]$ServiceAccount,
    [Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]+@[a-z0-9-]+\.iam\.gserviceaccount\.com$')][string]$BuildServiceAccount,
    [Parameter(Mandatory)][ValidateRange(1,2147483647)][int]$OpenAiSecretVersion,
    [Parameter(Mandatory)][ValidateRange(1,2147483647)][int]$DeviceSecretVersion
)
$ErrorActionPreference = 'Stop'
# Prerequisites: billing, Cloud Run/Cloud Build/Artifact Registry APIs, source-build IAM,
# and secret-level access for ServiceAccount. Secret values are never command arguments.
Get-Command gcloud -ErrorAction Stop | Out-Null
$taskBilling = & gcloud billing projects describe $ProjectId --format=json
if ($LASTEXITCODE -ne 0) { throw 'Unable to verify project billing.' }
if (-not ($taskBilling | ConvertFrom-Json).billingEnabled) { throw 'Enable billing for this project first.' }
& gcloud run deploy octopus-ai --project=$ProjectId --region=$Region --source=$PSScriptRoot `
    "--build-service-account=projects/$ProjectId/serviceAccounts/$BuildServiceAccount" `
    --service-account=$ServiceAccount --allow-unauthenticated --cpu=1 --memory=256Mi `
    --min=0 --max=1 --concurrency=8 --timeout=20s --cpu-throttling `
    "--set-secrets=OPENAI_API_KEY=octopus-openai-key:$OpenAiSecretVersion,AI_GATEWAY_TOKEN=octopus-device-token:$DeviceSecretVersion"
if ($LASTEXITCODE -ne 0) { throw 'Deployment failed; do not activate the Android gateway.' }
$taskUrl = & gcloud run services describe octopus-ai --project=$ProjectId --region=$Region --format='value(status.url)'
if ($LASTEXITCODE -ne 0 -or $taskUrl -notmatch '^https://') { throw 'No verified HTTPS service URL.' }
$taskHealth = Invoke-RestMethod -Uri "$taskUrl/health" -TimeoutSec 20
if ($taskHealth.configured -ne $true) { throw 'Gateway is not configured.' }
Write-Output "Gateway health passed: $taskUrl"
Write-Output 'Still required: authenticated recovery test, Android opt-in/provisioning, no-USB device test.'
