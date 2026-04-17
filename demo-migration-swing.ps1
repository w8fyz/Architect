Write-Host "Compiling..."
& .\gradlew.bat -q testClasses 2>&1 | Out-Null

$mainClasses = "build\classes\java\main"
$testClasses = "build\classes\java\test"
$cache = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1"

$needed = @(
    "hibernate-core", "jakarta.persistence-api", "HikariCP",
    "jedis", "commons-pool2", "jackson-databind", "jackson-core", "jackson-annotations",
    "classgraph", "postgresql",
    "jboss-logging", "byte-buddy", "antlr4-runtime",
    "hibernate-commons-annotations", "classmate",
    "jakarta.inject-api", "jakarta.transaction-api",
    "jandex", "jakarta.xml.bind-api", "jaxb-core", "jaxb-runtime",
    "istack-commons-runtime", "txw2", "angus-activation",
    "jakarta.activation-api"
)

$jars = @()
foreach ($dep in $needed) {
    $found = Get-ChildItem -Path $cache -Filter "$dep-*.jar" -Recurse -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($found) { $jars += $found.FullName }
}

$cp = "$mainClasses;$testClasses;" + ($jars -join ";")

Write-Host "Starting Migration Tool Demo..."
Write-Host "Close the Swing window or press Ctrl+C to stop."
Write-Host ""

& java -cp $cp sh.fyz.architect.test.MigrationToolDemo
