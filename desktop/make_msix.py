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

import struct

# content types: a Default per extension, an Override for extension-less files
known = {'png': 'image/png', 'dll': 'application/x-msdownload', 'exe': 'application/x-msdownload',
         'jar': 'application/java-archive', 'xml': 'application/xml'}
exts = sorted({os.path.splitext(n)[1][1:].lower() for n in files if os.path.splitext(n)[1]} | {'xml'})
ctypes = ['<?xml version="1.0" encoding="utf-8"?>',
          '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">']
for e in exts:
    ctypes.append(f'<Default Extension="{escape(e)}" ContentType="{known.get(e, "application/octet-stream")}"/>')
for n in sorted(files):
    base = n.rsplit('/', 1)[-1]
    if '.' not in base or base.endswith('.'):
        ctypes.append(f'<Override PartName="/{escape(n)}" ContentType="application/octet-stream"/>')
ctypes.append('<Override PartName="/AppxManifest.xml" ContentType="application/vnd.ms-appx.manifest+xml"/>')
ctypes.append('<Override PartName="/AppxBlockMap.xml" ContentType="application/vnd.ms-appx.blockmap+xml"/>')
ctypes.append('</Types>')

BLOCK = 65536
import zlib
DOS_TIME, DOS_DATE = 0, ((2024 - 1980) << 9) | (1 << 5) | 1


def deflate_blocks(data):
    """Raw deflate stream whose 64 KiB blocks are independently delimited (as makeappx does).
    Returns (compressed bytes, [compressed size of each block])."""
    c = zlib.compressobj(9, zlib.DEFLATED, -15)
    parts, sz = [], []
    n = (len(data) + BLOCK - 1) // BLOCK
    for i in range(n):
        chunk = c.compress(data[i * BLOCK:(i + 1) * BLOCK])
        chunk += c.flush(zlib.Z_FINISH if i == n - 1 else zlib.Z_FULL_FLUSH)
        parts.append(chunk)
        sz.append(len(chunk))
    return b''.join(parts), sz


class Zip:
    def __init__(self, path):
        self.f = open(path, 'wb')
        self.cd = []
        self.lfh = {}

    def add(self, name, data, deflate=True):
        nb = name.encode('utf-8')
        if deflate and data:
            body, bsz = deflate_blocks(data)
            method = 8
        else:
            body, bsz, method = data, [], 0
        off = self.f.tell()
        crc = zlib.crc32(data) & 0xffffffff
        self.f.write(struct.pack('<IHHHHHIIIHH', 0x04034b50, 20, 0, method, DOS_TIME, DOS_DATE,
                                 crc, len(body), len(data), len(nb), 0) + nb + body)
        self.cd.append(struct.pack('<IHHHHHHIIIHHHHHII', 0x02014b50, 20, 20, 0, method, DOS_TIME, DOS_DATE,
                                   crc, len(body), len(data), len(nb), 0, 0, 0, 0, 0, off) + nb)
        self.lfh[name] = 30 + len(nb)
        return bsz

    def close(self):
        start = self.f.tell()
        cd = b''.join(self.cd)
        self.f.write(cd)
        self.f.write(struct.pack('<IHHHHIIH', 0x06054b50, 0, 0, len(self.cd), len(self.cd), len(cd), start, 0))
        self.f.close()


if os.path.exists(out):
    os.remove(out)
z = Zip(out)
z.add('[Content_Types].xml', '\n'.join(ctypes).encode('utf-8'))
bm = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
      '<BlockMap xmlns="http://schemas.microsoft.com/appx/2010/blockmap" HashMethod="http://www.w3.org/2001/04/xmlenc#sha256">']
for arc, p in sorted(files.items()):
    data = open(p, 'rb').read()
    bsz = z.add(arc, data)
    bm.append(f'<File Name="{escape(arc.replace("/", chr(92)))}" Size="{len(data)}" LfhSize="{z.lfh[arc]}">')
    for i, off in enumerate(range(0, len(data), BLOCK)):
        h = base64.b64encode(hashlib.sha256(data[off:off + BLOCK]).digest()).decode()
        bm.append(f'<Block Hash="{h}" Size="{bsz[i]}"/>' if bsz else f'<Block Hash="{h}"/>')
    bm.append('</File>')
bm.append('</BlockMap>')
z.add('AppxBlockMap.xml', '\n'.join(bm).encode('utf-8'), deflate=False)
z.close()
print('wrote', out, os.path.getsize(out) // 1024 // 1024, 'MB')
