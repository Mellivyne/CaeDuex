"""Real TLS/codec integration checks, using temporary local certificates and no game account."""
import asyncio
import importlib.util
import os
from pathlib import Path
import shutil
import ssl
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
def java_tools():
    roots = [Path(value) for value in (os.environ.get('JAVA21_HOME'), os.environ.get('JAVA_HOME')) if value]
    compiler = shutil.which('javac')
    if compiler: roots.append(Path(compiler).resolve().parent.parent)
    cache = Path.home() / '.gradle/jdks'
    if cache.is_dir(): roots.extend(sorted(cache.glob('*21*')))
    for candidate in roots:
        binaries = candidate / 'bin'
        if (binaries / 'javac.exe').is_file() and (binaries / 'keytool.exe').is_file(): return binaries
    raise RuntimeError('Set JAVA21_HOME to a Java 21 or newer JDK')

JAVA = java_tools()

async def main():
    spec = importlib.util.spec_from_file_location('appcast_relay', ROOT / 'tools/appcast_relay.py')
    relay_module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(relay_module)
    with tempfile.TemporaryDirectory(prefix='cp-shared-check-') as directory:
        work = Path(directory)
        env = dict(os.environ, CP_TEST_DIR=str(work))
        script = r"""
$rsa=[System.Security.Cryptography.RSA]::Create(2048)
$request=[System.Security.Cryptography.X509Certificates.CertificateRequest]::new('CN=localhost',$rsa,[System.Security.Cryptography.HashAlgorithmName]::SHA256,[System.Security.Cryptography.RSASignaturePadding]::Pkcs1)
$san=[System.Security.Cryptography.X509Certificates.SubjectAlternativeNameBuilder]::new();$san.AddDnsName('localhost');$request.CertificateExtensions.Add($san.Build())
$cert=$request.CreateSelfSigned([DateTimeOffset]::UtcNow.AddMinutes(-1),[DateTimeOffset]::UtcNow.AddHours(1))
[IO.File]::WriteAllText((Join-Path $env:CP_TEST_DIR 'cert.pem'),$cert.ExportCertificatePem())
[IO.File]::WriteAllText((Join-Path $env:CP_TEST_DIR 'key.pem'),$rsa.ExportPkcs8PrivateKeyPem())
$cert.Dispose();$rsa.Dispose()
"""
        subprocess.run(['pwsh', '-NoProfile', '-Command', script], env=env, check=True, capture_output=True)
        subprocess.run([str(JAVA/'keytool.exe'), '-importcert', '-noprompt', '-alias', 'local-check', '-file', str(work/'cert.pem'), '-keystore', str(work/'trust.p12'), '-storetype', 'PKCS12', '-storepass', 'local-test-only'], check=True, capture_output=True)
        sources = [*sorted((ROOT/'src/main/java/com/cheatprogram/capture').glob('Shared*.java')), ROOT/'src/main/java/com/cheatprogram/capture/LatestFrameDecoder.java', ROOT/'tools/tests/SharedProjectionCheck.java']
        subprocess.run([str(JAVA/'javac.exe'), '--release', '21', '-d', str(work/'classes'), *map(str,sources)], check=True)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(work/'cert.pem',work/'key.pem')
        relay = relay_module.Relay()
        async with await asyncio.start_server(relay.handle, '127.0.0.1', 0, ssl=context) as server:
            port = server.sockets[0].getsockname()[1]
            options = [f'-Djavax.net.ssl.trustStore={work/"trust.p12"}', '-Djavax.net.ssl.trustStorePassword=local-test-only']
            if os.environ.get('CP_TEST_SOFTWARE') == '1': options.append('-Dcheatprogram.cast.encoder=libx264')
            process = await asyncio.create_subprocess_exec(str(JAVA/'java.exe'),*options,'-cp',str(work/'classes'),'com.cheatprogram.capture.SharedProjectionCheck',str(port),cwd=ROOT,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.STDOUT)
            try:
                output,_ = await asyncio.wait_for(process.communicate(),80)
                print(output.decode('utf-8',errors='replace'))
                if process.returncode: raise SystemExit(process.returncode)
            finally:
                if process.returncode is None: process.kill();await process.wait()

if __name__ == '__main__': asyncio.run(main())
