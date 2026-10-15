#!/usr/bin/env python3
"""Builds an (unsigned) MSIX package for the Microsoft Store from a Windows bundle folder.

usage: make_msix.py <bundle_dir> <icons_dir> <out.msix> <version a.b.c.d>
Identity values come from the Partner Center ("Product identity" page):
  MSIX_IDENTITY_NAME, MSIX_PUBLISHER, MSIX_PUBLISHER_DISPLAY
Files are stored uncompressed (equivalent to `makeappx pack /nc`), so no compression bookkeeping is needed.
"""
import base64, hashlib, os, subprocess, sys, tempfile, zipfile
from urllib.parse import quote
from xml.sax.saxutils import escape

bundle, icons, out, version = sys.argv[1:5]
name = os.environ.get('MSIX_IDENTITY_NAME', 'kroryan.Astrovia')
publisher = os.environ.get('MSIX_PUBLISHER', 'CN=kroryan')
pubdisp = os.environ.get('MSIX_PUBLISHER_DISPLAY', 'kroryan')

assets = {  # file -> size (square unless noted)
    'StoreLogo.png': (50, 50), 'Square44x44Logo.png': (44, 44), 'Square71x71Logo.png': (71, 71),
    'Square150x150Logo.png': (150, 150), 'Square310x310Logo.png': (310, 310), 'Wide310x150Logo.png': (310, 150),
}
src_icon = os.path.join(icons, 'astrovia-512.png')
tmp = tempfile.mkdtemp()
files = {}   # archive name -> local path
for root, _, fs in os.walk(bundle):
    for f in fs:
        p = os.path.join(root, f)
        files[os.path.relpath(p, bundle).replace(os.sep, '/')] = p
for fn, (w, h) in assets.items():
    p = os.path.join(tmp, fn)
    if w == h:
        subprocess.check_call(['convert', src_icon, '-resize', f'{w}x{h}', p])
    else:  # wide tile: icon centred on Astrovia night background
        subprocess.check_call(['convert', '-size', f'{w}x{h}', 'xc:#0B1020', '(', src_icon, '-resize', f'{h}x{h}', ')',
                               '-gravity', 'center', '-composite', p])
    files['Assets/' + fn] = p

manifest = f'''<?xml version="1.0" encoding="utf-8"?>
<Package xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
         xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10"
         xmlns:rescap="http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities"
         IgnorableNamespaces="uap rescap">
  <Identity Name="{escape(name)}" Publisher="{escape(publisher)}" Version="{version}" ProcessorArchitecture="x64"/>
  <Properties>
    <DisplayName>Astrovia</DisplayName>
    <PublisherDisplayName>{escape(pubdisp)}</PublisherDisplayName>
    <Logo>Assets\\StoreLogo.png</Logo>
    <Description>Astrovia - Open-source sky atlas</Description>
  </Properties>
  <Dependencies>
    <TargetDeviceFamily Name="Windows.Desktop" MinVersion="10.0.17763.0" MaxVersionTested="10.0.22631.0"/>
  </Dependencies>
  <Resources><Resource Language="en-us"/></Resources>
  <Applications>
    <Application Id="Astrovia" Executable="Astrovia.exe" EntryPoint="Windows.FullTrustApplication">
      <uap:VisualElements DisplayName="Astrovia" Description="Astrovia - Open-source sky atlas"
          BackgroundColor="#0B1020" Square150x150Logo="Assets\\Square150x150Logo.png" Square44x44Logo="Assets\\Square44x44Logo.png">
        <uap:DefaultTile Wide310x150Logo="Assets\\Wide310x150Logo.png" Square71x71Logo="Assets\\Square71x71Logo.png"
            Square310x310Logo="Assets\\Square310x310Logo.png"/>
      </uap:VisualElements>
    </Application>
  </Applications>
  <Capabilities>
    <Capability Name="internetClient"/>
    <rescap:Capability Name="runFullTrust"/>
  </Capabilities>
</Package>
'''
mpath = os.path.join(tmp, 'AppxManifest.xml')
open(mpath, 'w', encoding='utf-8').write(manifest)
files['AppxManifest.xml'] = mpath

ctypes = ['<?xml version="1.0" encoding="utf-8"?>',
          '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">']
exts = sorted({os.path.splitext(n)[1][1:].lower() for n in files if os.path.splitext(n)[1]})
known = {'png': 'image/png', 'xml': 'application/vnd.ms-appx.manifest+xml', 'dll': 'application/x-msdownload',
         'exe': 'application/x-msdownload', 'jar': 'application/java-archive'}
for e in exts:
    if e == 'xml':
        continue
    ctypes.append(f'<Default Extension="{e}" ContentType="{known.get(e, "application/octet-stream")}"/>')
ctypes.append('<Default Extension="xml" ContentType="application/octet-stream"/>')
ctypes.append('<Override PartName="/AppxManifest.xml" ContentType="application/vnd.ms-appx.manifest+xml"/>')
ctypes.append('<Override PartName="/AppxBlockMap.xml" ContentType="application/vnd.ms-appx.blockmap+xml"/>')
ctypes.append('</Types>')

# zip (stored). Local header size is needed in the block map, so write the payload first.
BLOCK = 65536
entries = []
zpath = out
if os.path.exists(zpath):
    os.remove(zpath)
with zipfile.ZipFile(zpath, 'w', zipfile.ZIP_STORED, allowZip64=True) as z:
    for arc, p in sorted(files.items()):
        data = open(p, 'rb').read()
        zi = zipfile.ZipInfo(arc, date_time=(2024, 1, 1, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_STORED
        z.writestr(zi, data)
        lfh = 30 + len(arc.encode('utf-8'))
        blocks = [base64.b64encode(hashlib.sha256(data[i:i + BLOCK]).digest()).decode()
                  for i in range(0, len(data), BLOCK)] or [base64.b64encode(hashlib.sha256(b'').digest()).decode()]
        entries.append((arc, len(data), lfh, blocks))
    bm = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
          '<BlockMap xmlns="http://schemas.microsoft.com/appx/2010/blockmap" HashMethod="http://www.w3.org/2001/04/xmlenc#sha256">']
    for arc, size, lfh, blocks in entries:
        bm.append(f'<File Name="{escape(arc.replace("/", chr(92)))}" Size="{size}" LfhSize="{lfh}">')
        bm.extend(f'<Block Hash="{h}"/>' for h in blocks)
        bm.append('</File>')
    bm.append('</BlockMap>')
    z.writestr('AppxBlockMap.xml', '\n'.join(bm).encode('utf-8'))
    z.writestr('[Content_Types].xml', '\n'.join(ctypes).encode('utf-8'))
print('wrote', out, os.path.getsize(out) // 1024 // 1024, 'MB')
