# Windows redesign references

Approved composition references for the Windows client. These images are visual guidance; the implementation specification and the code remain the source of behavior.

| Page | Dark | Light |
|---|---|---|
| Home | [home-dark.png](references/home-dark.png) | [home-light.png](references/home-light.png) |
| Routes | [routes-dark.png](references/routes-dark.png) | [routes-light.png](references/routes-light.png) |
| Profile | [profile-dark.png](references/profile-dark.png) | [profile-light.png](references/profile-light.png) |
| Settings | [settings-dark.png](references/settings-dark.png) | [settings-light.png](references/settings-light.png) |

The interface stays on Avalonia 12. The four standard pages use the native Avalonia shell and controls. Home uses the locally bundled Windows WebView2 atlas, with VPN state and actions supplied by the existing C# client. Android atlas assets are unchanged.

See the task Roadmap for implementation status and the remaining native acceptance checks.
