# Windows package third-party notices

## Amnezia Box VPN engine

The `sing-box.exe` engine is built from Amnezia Box commit
`16724484cfa00d9b2c14d8280dc055564e2e12ba` with the `with_awg` build tag.
It is distributed under the license reproduced in `source/AMNEZIA-BOX-LICENSE.txt`;
the corresponding upstream source tree is included in `source/`.

The upstream license includes an additional condition restricting use of the
upstream application's name or implied association in derivative works. The
DEYTT client uses its own product identity and describes AmneziaWG only as a
supported protocol. Review that condition before public distribution.

The engine statically includes its Windows tunnel dependencies. Their source
and license files are present in the corresponding upstream source archive.

## Fonts

Inter Tight, JetBrains Mono, and Unbounded are included under the SIL Open Font
License. Their license files are shipped alongside the font assets in the app.

## Avalonia

The Windows interface uses Avalonia packages. Their package notices and
license terms are available from the package metadata at
https://www.nuget.org/packages/Avalonia/12.1.3 and
https://www.nuget.org/packages/Avalonia.Desktop/12.1.3.
