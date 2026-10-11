using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Fonts;
using Avalonia.Markup.Xaml;
using Avalonia.Media.Imaging;
using Avalonia.Automation;
using Avalonia.Interactivity;
using Avalonia.VisualTree;
using System.Diagnostics;
using System.Globalization;
using System.Reflection;
using System.Text.Json;
using System.Threading;
using DeyttConnect.Windows.Controls;
using Microsoft.Web.WebView2.Core;
using DeyttConnect.Protocol;
using DeyttConnect.Windows;
using DeyttConnect.Windows.Services;
using DeyttConnect.Windows.Views;

namespace DeyttConnect.Windows.QA;

internal sealed class QaApp : Application
{
    public override void Initialize() => AvaloniaXamlLoader.Load(this);

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            var options = QaOptions.Parse(desktop.Args ?? []);
            desktop.MainWindow = BuildWindow(options);
            if (desktop.MainWindow is MainWindow window)
            {
                if (options.VerifyRouteSelection)
                    window.Opened += async (_, _) => await RunRouteSelectionQaAsync(window, options);
                else if (options.VerifyNavigation || options.VerifyZoom || options.CaptureMotion)
                    window.Opened += async (_, _) => await RunNavigationQaAsync(window, options);
                else if (options.CaptureHomePreview is { Length: > 0 } capturePath)
                    window.Opened += (_, _) => _ = options.Tab == "home" && !Environment.GetCommandLineArgs().Contains("--setup-preview", StringComparer.Ordinal)
                        ? CaptureHomePreviewAsync(window, capturePath, options)
                        : CapturePageAsync(window, capturePath, options);
            }
        }

        base.OnFrameworkInitializationCompleted();
    }

    private static MainWindow BuildWindow(QaOptions options)
    {
        var routes = new[]
        {
            new WindowsRoute("auto", "qa:auto", "AUTO", "Автоподбор", "✦", "AUTO", "Автоподбор"),
            new WindowsRoute("ru-de", "qa:ru-de", "RU-DE", "Россия → Германия", "🇷🇺→🇩🇪", "CHAIN", "RU → DE"),
            new WindowsRoute("nl-vless", "qa:nl-vless", "NL", "Нидерланды", "🇳🇱", "VLESS", "VLESS"),
            new WindowsRoute("nl-trojan", "qa:nl-trojan", "NL", "Нидерланды", "🇳🇱", "TROJAN", "Trojan"),
            new WindowsRoute("nl-hysteria2", "qa:nl-hysteria2", "NL", "Нидерланды", "🇳🇱", "HYSTERIA2", "Hysteria 2"),
            new WindowsRoute("nl-awg31", "qa:nl-awg31", "NL", "Нидерланды", "🇳🇱", "AWG31", "AmneziaWG 3.1"),
            new WindowsRoute("de-vless", "qa:de-vless", "DE", "Германия", "🇩🇪", "VLESS", "VLESS"),
            new WindowsRoute("de-trojan", "qa:de-trojan", "DE", "Германия", "🇩🇪", "TROJAN", "Trojan"),
            new WindowsRoute("de-hysteria2", "qa:de-hysteria2", "DE", "Германия", "🇩🇪", "HYSTERIA2", "Hysteria 2"),
            new WindowsRoute("ru-vless", "qa:ru-vless", "RU", "Россия", "🇷🇺", "VLESS", "VLESS"),
            new WindowsRoute("ru-trojan", "qa:ru-trojan", "RU", "Россия", "🇷🇺", "TROJAN", "Trojan"),
            new WindowsRoute("ru-hysteria2", "qa:ru-hysteria2", "RU", "Россия", "🇷🇺", "HYSTERIA2", "Hysteria 2"),
            new WindowsRoute("fi-vless", "qa:fi-vless", "FI", "Финляндия", "🇫🇮", "VLESS", "VLESS"),
            new WindowsRoute("fi-trojan", "qa:fi-trojan", "FI", "Финляндия", "🇫🇮", "TROJAN", "Trojan"),
            new WindowsRoute("fi-hysteria2", "qa:fi-hysteria2", "FI", "Финляндия", "🇫🇮", "HYSTERIA2", "Hysteria 2"),
            new WindowsRoute("it-vless", "qa:it-vless", "IT", "Италия", "🇮🇹", "VLESS", "VLESS"),
            new WindowsRoute("it-trojan", "qa:it-trojan", "IT", "Италия", "🇮🇹", "TROJAN", "Trojan"),
            new WindowsRoute("it-hysteria2", "qa:it-hysteria2", "IT", "Италия", "🇮🇹", "HYSTERIA2", "Hysteria 2"),
        };
        var fixtureSubscription = options.SubscriptionWarning
            ? new TelegramSubscription(true, true, "qa", "deytt./ultra",
                DateTimeOffset.UtcNow.AddDays(7).ToString("O", CultureInfo.InvariantCulture),
                false, 10, 10, null, 31L * 1024 * 1024 * 1024)
            : new TelegramSubscription(true, true, "qa", "QA Demo", "2099-12-31T00:00:00Z",
                false, 3, 1, null, null);
        var account = options.SignedIn
            ? new TelegramAccount("qa_fixture", "QA Demo", false,
                fixtureSubscription)
            : null;
        var subscription = options.SignedIn
            ? new TelegramKeysSnapshot(true, false, 0,
                [new TelegramHappDevice(0, false, "QA fixture", "Synthetic desktop", "now")],
                "{\"qa_fixture\":true}", routes, [])
            : null;
        var tunnel = options.State switch
        {
            "connected" => new WindowsTunnelSnapshot("connected", "QA fixture · synthetic state", "qa:" + options.Route,
                HealthCheckedAt: DateTimeOffset.UtcNow),
            "connecting" => new WindowsTunnelSnapshot("starting", "QA fixture · synthetic state", "qa:" + options.Route),
            "error" => new WindowsTunnelSnapshot("error", "Причина завершения ядра не получена. Код завершения: 0x00000001."),
            _ => new WindowsTunnelSnapshot("disconnected", "QA fixture · synthetic state"),
        };
        var fixtureArgs = Environment.GetCommandLineArgs();
        var activeIndex = Array.IndexOf(fixtureArgs, "--active-route");
        if (activeIndex >= 0 && activeIndex + 1 < fixtureArgs.Length)
        {
            var confirmed = routes.FirstOrDefault(route => route.Id == fixtureArgs[activeIndex + 1] && route.CountryCode != "AUTO")
                ?? throw new ArgumentException("--active-route must name a concrete fixture route.");
            tunnel = tunnel with { ActiveRouteTag = confirmed.Tag };
        }
        var probeResult = options.ProbeStage is { } stage
            ? new WindowsRouteProbeResult("qa:" + options.Route,
                stage is "waiting_speed" or "download" or "complete" ? 42 : null,
                stage == "complete" ? 4_000_000 : null,
                stage == "error" ? "Synthetic route check error." : null, stage,
                stage is "latency" or "retry" ? 2 : null,
                stage == "download" ? 5 * 1024 * 1024 : null,
                stage == "download" ? 32 * 1024 * 1024 : null)
            : null;
        var fixture = new QaHomeFixture(options.SignedIn, options.Language, options.Route, options.Tab,
            account, subscription, routes, tunnel, probeResult,
            RouteProbeInProgress: options.ProbeStage is "latency" or "retry" or "waiting_speed" or "download",
            ExpandedRouteCountry: options.Tab == "routes" ? "NL" : null,
            ThemePreference: options.Theme);
        var window = new MainWindow(fixture)
        {
            Title = $"deytt./connect · QA fixture ({(options.SignedIn ? "signed-in" : "signed-out")}, {options.State}, {options.Theme})",
            Width = options.Width,
            Height = options.Height,
        };
        if (fixtureArgs.Contains("--setup-preview", StringComparer.Ordinal))
        {
            const BindingFlags setupFlags = BindingFlags.Instance | BindingFlags.NonPublic;
            var view = new SetupWindow(new SetupWindowOptions
            {
                AllowCancel = false, RestoreExistingSession = false, Language = options.Language,
                ImportUrlAsync = (_, _, _) => throw new InvalidOperationException("Remote imports are disabled in the QA fixture."),
            });
            typeof(MainWindow).GetField("_activeSetupView", setupFlags)!.SetValue(window, view);
            var showTab = typeof(MainWindow).GetMethod("ShowTab", setupFlags)!;
            showTab.Invoke(window, [Enum.Parse(showTab.GetParameters()[0].ParameterType, "Setup"), false]);
        }
        if (options.WithOrigin)
        {
            const BindingFlags flags = BindingFlags.Instance | BindingFlags.NonPublic;
            typeof(MainWindow).GetProperty("_mapRegionEnabled", flags)!.SetValue(window, true);
            typeof(MainWindow).GetProperty("_mapRegionConsentGranted", flags)!.SetValue(window, true);
            typeof(MainWindow).GetProperty("_mapOriginLocation", flags)!.SetValue(window,
                new WindowsNetworkLocation(54.7388, 55.9721, "Уфа", "", "RU"));
            typeof(MainWindow).GetMethod("UpdateHomeGlobePresentation", flags)!.Invoke(window, null);
        }

        var content = (Control)window.Content!;
        window.Content = null;
        var shell = new Grid { RowDefinitions = new RowDefinitions("Auto,*") };
        shell.Children.Add(new Border
        {
            Name = "QaFixtureBanner",
            Background = new SolidColorBrush(Color.Parse("#63391D")),
            Padding = new Thickness(16, 8),
            Child = new TextBlock
            {
                Text = $"QA FIXTURE · VPN: {options.State.ToUpperInvariant()} · SYNTHETIC · NO LIVE SESSION",
                FontFamily = "fonts:DEYTT#JetBrains Mono",
                FontSize = 12,
                FontWeight = FontWeight.SemiBold,
                Foreground = Brushes.White,
                HorizontalAlignment = HorizontalAlignment.Center,
            },
        });
        Grid.SetRow(content, 1);
        shell.Children.Add(content);
        window.Content = shell;
        return window;
    }

    private static async Task CapturePageAsync(MainWindow window, string path, QaOptions options)
    {
        await Task.Delay(900);
        if (options.Tab == "profile")
        {
            var iconKinds = window.GetVisualDescendants()
                .Where(control => control.GetType().Name == "NavigationIcon")
                .Select(control => control.GetType().GetProperty("Kind")?.GetValue(control)?.ToString())
                .Where(kind => kind is not null).ToHashSet(StringComparer.Ordinal);
            Console.WriteLine("QA Profile disclosure vector icons: " +
                (iconKinds.Contains("Devices") && iconKinds.Contains("Help")));
        }
        if (options.Tab == "routes" && options.State == "disconnected")
        {
            Button? FindButton(string id) => window.GetVisualDescendants().OfType<Button>()
                .FirstOrDefault(button => AutomationProperties.GetAutomationId(button) == id);
            FindButton("RouteOption-nl-hysteria2")?.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            var selected = typeof(MainWindow).GetProperty("_selectedRoute", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(window);
            Console.WriteLine("QA route row selects Hysteria: " + Equals(selected, "nl-hysteria2"));
            window.FindControl<Button>("TopRoutesNav")!.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            FindButton("RouteOption-" + options.Route)?.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            window.FindControl<Button>("TopRoutesNav")!.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            FindButton("RouteCountry-NL")?.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            FindButton("RouteCountry-NL")?.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            await Task.Delay(150);
            Console.WriteLine("QA country reopens with route controls: " + (FindButton("RouteOption-nl-awg31") is { IsEffectivelyVisible: true }));
        }
        var control = (Control)window.Content!;
        using var bitmap = new RenderTargetBitmap(new PixelSize((int)control.Bounds.Width, (int)control.Bounds.Height), new Vector(96, 96));
        bitmap.Render(control);
        var fullPath = Path.GetFullPath(path);
        Directory.CreateDirectory(Path.GetDirectoryName(fullPath)!);
        await using var output = File.Create(fullPath);
        bitmap.Save(output, PngBitmapEncoderOptions.Default);
        window.Close();
    }

    private static async Task CaptureHomePreviewAsync(MainWindow window, string path, QaOptions options)
    {
        var (flatPanel, zoom, immersive, measureFrames, selectPacket) =
            (options.FlatPanel, options.Zoom, options.Immersive, options.MeasureFrames, options.InspectTrafficLine);
        var field = typeof(MainWindow).GetField("_routeGlobe", BindingFlags.Instance | BindingFlags.NonPublic);
        var deadline = DateTimeOffset.UtcNow.AddSeconds(30);
        while (DateTimeOffset.UtcNow < deadline)
        {
            if (field?.GetValue(window) is RouteGlobeWebView globe)
            {
                Bitmap? preview = await globe.CapturePreviewAsync();
                if (preview is not null)
                {
                    preview.Dispose();
                    if (typeof(RouteGlobeWebView).GetField("_coreWebView2", BindingFlags.Instance | BindingFlags.NonPublic)
                        ?.GetValue(globe) is CoreWebView2 core)
                    {
                        var commands = new List<string>();
                        if (Environment.GetCommandLineArgs().Contains("--reduce-motion", StringComparer.Ordinal))
                            commands.Add("window.deyttSetMapReducedMotion(true);");
                        if (options.RefreshMetrics)
                        {
                            var tag = "qa:" + options.Route;
                            typeof(MainWindow).GetMethod("ApplyProbeProgress", BindingFlags.Instance | BindingFlags.NonPublic)!
                                .Invoke(window, new object[] { new WindowsRouteProbeResult[] { new(tag, 88, 6_000_000, null, "complete") }, new[] { tag } });
                            Console.WriteLine("QA Home metric refresh: " + (globe.HomePresentation?.Latency == "88" && globe.HomePresentation?.Speed == "48"));
                        }
                        if (measureFrames)
                        {
                            commands.Add("window.deyttMapAtlas.canvas.addEventListener('wheel',e=>{if(e.isTrusted){e.preventDefault();e.stopImmediatePropagation();}},{capture:true,passive:false});");
                            commands.Add("(function(){const a=window.deyttMapAtlas;if(!a||a.__qaMeasured)return;const r=a.render.bind(a);a.__qaFrames=[];a.__qaMeasured=true;a.__qaPrevious=0;a.render=function(t){const s=performance.now();const v=r(t);const e=performance.now();a.__qaFrames.push({t:t,ms:e-s});if(a.__qaFrames.length>360)a.__qaFrames.shift();a.__qaPrevious=t;return v;};setTimeout(()=>{a.__qaFrames=[];a.__qaStaticBefore=a.staticRenderCount;},1200);})();");
                        }
                        if (options.MotionSample)
                            commands.Add("setTimeout(()=>{const a=window.deyttMapAtlas;for(let i=0;i<2;i++)a.canvas.dispatchEvent(new WheelEvent('wheel',{deltaY:-100,bubbles:true,cancelable:true}));},1500);setTimeout(()=>{const a=window.deyttMapAtlas;a.targetLon+=12;a.targetLat+=4;a.start();},2200);");
                        if (options.FocusPanel)
                            commands.Add("document.querySelector('.home-action')?.focus();");
                        if (zoom is { } targetZoom)
                        {
                            var zoomValue = targetZoom.ToString("0.##", CultureInfo.InvariantCulture);
                            commands.Add($"if(window.deyttMapAtlas){{window.deyttMapAtlas.zoom={zoomValue};window.deyttMapAtlas.targetZoom={zoomValue};window.deyttMapAtlas.markMapDirty();window.deyttMapAtlas.start();}}");
                        }
                        commands.Add(flatPanel
                            ? "const p=document.querySelector('.home-panel'); if(p){p.style.setProperty('--panel-angle','0deg'); p.style.setProperty('--panel-counter-angle','0deg');}"
                            : "document.querySelector('.home-panel')?.getBoundingClientRect();");
                        if (immersive)
                            commands.Add("document.dispatchEvent(new KeyboardEvent('keydown',{key:' ',code:'Space',bubbles:true,cancelable:true}));");
                        if (selectPacket)
                            commands.Add("setTimeout(function(){const a=window.deyttMapAtlas,s=a?.routeHitSegments?.filter(s=>s.stream.id==='upload'&&s.a.z>.4&&s.b.z>.4).find((s,i,list)=>i===Math.floor(list.length/2));if(!a||!s)return;a.dragDistance=0;const x=(s.a.x+s.b.x)/2,y=(s.a.y+s.b.y)/2,b=a.canvas.getBoundingClientRect();a.updateHover({x,y});a.__qaHoverHighlighted=a.hoveredTrafficStream==='upload';a.__qaStaticBeforeClick=a.staticRenderCount;a.canvas.dispatchEvent(new MouseEvent('click',{clientX:b.left+x,clientY:b.top+y,detail:1,bubbles:true}));a.__qaHintShown=!a.root.querySelector('[data-traffic-hint]').hidden;a.canvas.dispatchEvent(new MouseEvent('dblclick',{clientX:b.left+x,clientY:b.top+y,detail:2,bubbles:true}));a.__qaStaticStableAfterClick=a.staticRenderCount===a.__qaStaticBeforeClick;},1000)");
                        await core.ExecuteScriptAsync(string.Join(";", commands));
                        if (options.VerifyInteractions)
                        {
                            var zoomSteps = await core.ExecuteScriptAsync("""
                                (function(){
                                  const a=window.deyttMapAtlas,c=a.canvas,z=a.zoom,t=a.targetZoom;
                                  function step(from){a.zoom=from;a.targetZoom=from;c.dispatchEvent(new WheelEvent('wheel',{deltaY:-120,bubbles:true,cancelable:true}));return a.targetZoom/from;}
                                  const atOne=step(1),atTwo=step(2);
                                  a.zoom=z;a.targetZoom=t;a.markMapDirty();a.start();
                                  return JSON.stringify({one:atOne,two:atTwo,equal:Math.abs(atOne-atTwo)<.000001});
                                })()
                                """);
                            Console.WriteLine("QA uniform wheel increment: " + (JsonSerializer.Deserialize<string>(zoomSteps) ?? zoomSteps));
                            await core.ExecuteScriptAsync("""
                                window.__qaInteractionResult=null;
                                (async function(){
                                  const a=window.deyttMapAtlas,c=a.canvas;
                                  const active=a.trafficActive;
                                  a.setTrafficActive(false);a.velocityLon=0;
                                  a.targetLon=a.centerLon;a.targetLat=a.centerLat;a.targetZoom=a.zoom;
                                  a.targetCenterX=a.centerX;a.targetCenterY=a.centerY;
                                  await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
                                  a.cameraMoving=false;a.renderStatic(a.colors());
                                  const geography=a.staticCanvas.toDataURL(),ratio=a.pixelRatio;
                                  const capture=c.setPointerCapture;
                                  c.setPointerCapture=()=>{};
                                  const before=a.renderCount,b=c.getBoundingClientRect();
                                  c.dispatchEvent(new PointerEvent('pointerdown',{pointerId:77,button:0,clientX:b.left+a.centerX,clientY:b.top+a.centerY}));
                                  c.setPointerCapture=capture;
                                  await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
                                   const painted=a.staticContext.getImageData(Math.round((a.centerX+(a.staticPadding||0))*a.pixelRatio),Math.round((a.centerY+(a.staticPadding||0))*a.pixelRatio),1,1).data[3]>0;
                                  const held=a.dragging&&a.renderCount>before&&painted;
                                  const stableGeography=a.pixelRatio===ratio&&a.staticCanvas.toDataURL()===geography;
                                  c.dispatchEvent(new PointerEvent('lostpointercapture',{pointerId:77}));
                                  const released=!a.dragging&&a.activePointers.size===0;
                                  a.setTrafficActive(active);
                                  window.__qaInteractionResult={heldGlobeVisible:held,lostCaptureReleased:released,stableGeography};
                                })()
                                """);
                            // Read after the promise has had time to paint the held frame.
                            await Task.Delay(180);
                            var interactionResult = await core.ExecuteScriptAsync("window.__qaInteractionResult");
                            Console.WriteLine("QA globe hold/capture: " + interactionResult);
                            using var interaction = JsonDocument.Parse(interactionResult);
                            if (!interaction.RootElement.GetProperty("stableGeography").GetBoolean())
                                throw new InvalidOperationException("Globe geography changed during pointer hold.");
                        }
                        if (options.HoverSelector is { } selector)
                        {
                            await core.CallDevToolsProtocolMethodAsync("DOM.enable", "{}");
                            await core.CallDevToolsProtocolMethodAsync("CSS.enable", "{}");
                            using var document = JsonDocument.Parse(await core.CallDevToolsProtocolMethodAsync("DOM.getDocument", "{}"));
                            using var match = JsonDocument.Parse(await core.CallDevToolsProtocolMethodAsync("DOM.querySelector",
                                JsonSerializer.Serialize(new { nodeId = document.RootElement.GetProperty("root").GetProperty("nodeId").GetInt32(), selector })));
                            await core.CallDevToolsProtocolMethodAsync("CSS.forcePseudoState", JsonSerializer.Serialize(new
                            {
                                nodeId = match.RootElement.GetProperty("nodeId").GetInt32(), forcedPseudoClasses = new[] { "hover" }
                            }));
                        }
                    }
                    await Task.Delay(measureFrames
                        ? 3200
                        : (selectPacket ? 1400 : immersive ? 850 : 700));
                    if ((options.HoverSelector is not null || options.FocusPanel) &&
                        typeof(RouteGlobeWebView).GetField("_coreWebView2", BindingFlags.Instance | BindingFlags.NonPublic)
                            ?.GetValue(globe) is CoreWebView2 hoverCore)
                        Console.WriteLine("QA whole-panel hover/focus: " + await hoverCore.ExecuteScriptAsync("({panel:getComputedStyle(document.querySelector('.home-panel')).transform,child:getComputedStyle(document.querySelector('#home-city')).transform})"));
                    if (typeof(RouteGlobeWebView).GetField("_coreWebView2", BindingFlags.Instance | BindingFlags.NonPublic)
                        ?.GetValue(globe) is CoreWebView2 layoutCore)
                    {
                        Console.WriteLine("QA map layout: " + await layoutCore.ExecuteScriptAsync("({zoom:window.deyttMapAtlas.zoom,compact:document.querySelector('.home-compact').getAttribute('aria-hidden'),immersive:window.deyttMapAtlas.root.classList.contains('is-immersive'),angle:window.deyttMapAtlas.root.style.getPropertyValue('--panel-angle'),panel:document.querySelector('.home-panel-shell').getBoundingClientRect().toJSON(),globe:document.querySelector('.network-atlas__canvas-wrap').getBoundingClientRect().toJSON(),miniLatency:document.querySelector('#compact-latency').textContent,miniSpeed:document.querySelector('#compact-speed').textContent})"));
                        var labelValidation = await layoutCore.ExecuteScriptAsync("""
                            (()=>{
                              const a=window.deyttMapAtlas,labels=a.labelBoxes||[];
                              const overlap=(x,y)=>Math.min(x.left+x.width,y.left+y.width)>Math.max(x.left,y.left)&&Math.min(x.top+x.height,y.top+y.height)>Math.max(x.top,y.top);
                              const crossing=(l,r)=>{const side=(p,q,s)=>(q.x-p.x)*(s.y-p.y)-(q.y-p.y)*(s.x-p.x);const [p,q]=l.leader,[s,t]=r.leader;return side(p,q,s)*side(p,q,t)<0&&side(s,t,p)*side(s,t,q)<0;};
                              let overlaps=0,crossings=0;for(let i=0;i<labels.length;i++)for(let j=i+1;j<labels.length;j++){if(overlap(labels[i],labels[j]))overlaps++;if(crossing(labels[i],labels[j]))crossings++;}
                              return {labels:labels.map(l=>l.key),overlaps,crossings,clipped:labels.filter(l=>l.left<0||l.top<0||l.left+l.width>a.width||l.top+l.height>a.height).length,fullscreen:a.root.classList.contains('is-immersive'),zoom:a.zoom,dockZoom:a.root.dataset.dockZoom};
                            })()
                            """);
                        Console.WriteLine("QA callout validation: " + labelValidation);
                        using var labelReport = JsonDocument.Parse(labelValidation);
                        if (labelReport.RootElement.GetProperty("overlaps").GetInt32() != 0 || labelReport.RootElement.GetProperty("clipped").GetInt32() != 0)
                            throw new InvalidOperationException("Rendered map callouts overlap or clip.");
                    }
                    if (immersive && window.FindControl<Border>("QaFixtureBanner") is { } banner)
                        banner.IsVisible = false;
                    preview = await globe.CapturePreviewAsync();
                    if (preview is null)
                        continue;
                    var fullPath = Path.GetFullPath(path);
                    Directory.CreateDirectory(Path.GetDirectoryName(fullPath)!);
                    await using var output = File.Create(fullPath);
                    preview.Save(output, PngBitmapEncoderOptions.Default);
                    preview.Dispose();
                    if (selectPacket && typeof(RouteGlobeWebView).GetField("_coreWebView2", BindingFlags.Instance | BindingFlags.NonPublic)?.GetValue(globe) is CoreWebView2 trafficCore)
                    {
                        await trafficCore.ExecuteScriptAsync("window.deyttMapAtlas.__qaFlowBefore=JSON.stringify(window.deyttMapAtlas.packetHits.slice(0,3).map(p=>[p.x,p.y]));");
                        await Task.Delay(180);
                        var verification = await trafficCore.ExecuteScriptAsync("(function(){const a=window.deyttMapAtlas;return JSON.stringify({singleClickHint:a.__qaHintShown,hoverHighlight:a.__qaHoverHighlighted,onlySelectedLine:a.routeHitSegments.length>0&&a.routeHitSegments.every(s=>s.stream.id==='upload'),onlySelectedDots:a.packetHits.length>0&&a.packetHits.every(p=>p.stream.id==='upload'),flowStillMoves:a.__qaFlowBefore!==JSON.stringify(a.packetHits.slice(0,3).map(p=>[p.x,p.y])),animationContinues:a.trafficPausedAt==null&&a.inspectingStream==='upload',mapStableOnOpen:a.__qaStaticStableAfterClick,card:!a.root.querySelector('[data-traffic-inspector]').hidden,legendVisible:!!a.root.querySelector('[data-traffic-legend]'),compactMetrics:!!a.root.querySelector('#compact-latency')&&!!a.root.querySelector('#compact-speed'),panelAngle:a.root.style.getPropertyValue('--panel-angle'),zoom:a.zoom,canvasRight:a.canvas.getBoundingClientRect().right,rootRight:a.root.getBoundingClientRect().right});})()");
                        Console.WriteLine("QA line-only live inspector: " + (JsonSerializer.Deserialize<string>(verification) ?? verification));
                        var lineVerification = await trafficCore.ExecuteScriptAsync("(function(){const a=window.deyttMapAtlas;a.resumeTraffic();a.overlayDirty=true;a.renderOverlay(a.colors());const s=a.routeHitSegments.filter(s=>s.stream.id==='download'&&s.a.z>.4&&s.b.z>.4).find((s,i,list)=>i===Math.floor(list.length/2));if(!s)return 'no line';const x=(s.a.x+s.b.x)/2,y=(s.a.y+s.b.y)/2,b=a.canvas.getBoundingClientRect(),args={clientX:b.left+x,clientY:b.top+y,detail:2,bubbles:true};a.canvas.dispatchEvent(new MouseEvent('dblclick',args));a.overlayDirty=true;a.renderOverlay(a.colors());return JSON.stringify({secondLineSelected:a.inspectingStream==='download',onlySelectedLine:a.routeHitSegments.length>0&&a.routeHitSegments.every(l=>l.stream.id==='download'),animationContinues:a.trafficPausedAt==null});})()");
                        Console.WriteLine("QA second traffic line: " + (JsonSerializer.Deserialize<string>(lineVerification) ?? lineVerification));
                    }
                    if (measureFrames && typeof(RouteGlobeWebView).GetField("_coreWebView2", BindingFlags.Instance | BindingFlags.NonPublic)
                        ?.GetValue(globe) is CoreWebView2 profileCore)
                    {
                        var stats = await profileCore.ExecuteScriptAsync("(function(){const f=window.deyttMapAtlas?.__qaFrames||[];const intervals=f.slice(1).map((v,i)=>v.t-f[i].t).sort((a,b)=>a-b);const costs=f.map(v=>v.ms).sort((a,b)=>a-b);const p=(a,q)=>a.length?a[Math.min(a.length-1,Math.floor((a.length-1)*q))]:0;return JSON.stringify({frames:f.length,staticPaints:window.deyttMapAtlas.staticRenderCount-(window.deyttMapAtlas.__qaStaticBefore||0),zoom:window.deyttMapAtlas.zoom,targetZoom:window.deyttMapAtlas.targetZoom,cameraMoving:window.deyttMapAtlas.cameraMoving,avgFps:intervals.length?1000/(intervals.reduce((s,v)=>s+v,0)/intervals.length):0,p95FrameMs:p(intervals,.95),p95RenderMs:p(costs,.95),over20ms:intervals.filter(v=>v>20).length});})()");
                        Console.WriteLine("QA atlas frame sample: " + (JsonSerializer.Deserialize<string>(stats) ?? stats));
                    }
                    if (immersive && typeof(RouteGlobeWebView).GetField("_coreWebView2", BindingFlags.Instance | BindingFlags.NonPublic)
                        ?.GetValue(globe) is CoreWebView2 immersiveCore)
                    {
                        await immersiveCore.ExecuteScriptAsync("window.deyttMapAtlas.resumeTraffic();document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',bubbles:true,cancelable:true}));");
                        await Task.Delay(250);
                        var immersiveField = typeof(MainWindow).GetField("_mapImmersive", BindingFlags.Instance | BindingFlags.NonPublic);
                        Console.WriteLine("QA Escape exits immersive mode: " + Equals(immersiveField?.GetValue(window), false));
                    }
                    window.Close();
                    return;
                }
            }

            await Task.Delay(250);
        }

        Console.Error.WriteLine("QA home preview was not ready before the 30-second capture deadline.");
        window.Close();
    }

    private static async Task RunRouteSelectionQaAsync(MainWindow window, QaOptions options)
    {
        const BindingFlags flags = BindingFlags.Instance | BindingFlags.NonPublic;
        var heartbeat = new Avalonia.Threading.DispatcherTimer { Interval = TimeSpan.FromMilliseconds(12) };
        var previousBeat = Stopwatch.GetTimestamp();
        double maxHeartbeatMs = 0;
        heartbeat.Tick += (_, _) =>
        {
            var now = Stopwatch.GetTimestamp();
            maxHeartbeatMs = Math.Max(maxHeartbeatMs, Stopwatch.GetElapsedTime(previousBeat, now).TotalMilliseconds);
            previousBeat = now;
        };
        heartbeat.Start();
        try
        {
            var showTab = typeof(MainWindow).GetMethod("ShowTab", flags)!;
            var tabType = showTab.GetParameters()[0].ParameterType;
            void Routes() => showTab.Invoke(window, [Enum.Parse(tabType, "Routes"), true]);
            Button? FindButton(string id) => window.FindControl<Grid>("PageHost")!.GetVisualDescendants()
                .OfType<Button>().FirstOrDefault(button => button.IsEffectivelyVisible &&
                    AutomationProperties.GetAutomationId(button) == id);
            var results = new List<object>();
            CoreWebView2? retainedCore = null;
            RouteGlobeWebView? retainedGlobe = null;
            var simulateTimeout = Environment.GetCommandLineArgs().Contains("--simulate-timeout-push", StringComparer.Ordinal);
            if (simulateTimeout || Environment.GetCommandLineArgs().Contains("--simulate-stalled-push", StringComparer.Ordinal))
            {
                showTab.Invoke(window, [Enum.Parse(tabType, "Home"), true]);
                var loading = Stopwatch.StartNew();
                RouteGlobeWebView? injectingGlobe = null;
                CoreWebView2? injectingCore = null;
                while (loading.Elapsed < TimeSpan.FromSeconds(15))
                {
                    await Task.Delay(50);
                    injectingGlobe = typeof(MainWindow).GetField("_routeGlobe", flags)?.GetValue(window) as RouteGlobeWebView;
                    injectingCore = injectingGlobe is null ? null : typeof(RouteGlobeWebView).GetField("_coreWebView2", flags)?.GetValue(injectingGlobe) as CoreWebView2;
                    if (injectingCore is not null && typeof(RouteGlobeWebView).GetField("_pageReady", flags)?.GetValue(injectingGlobe) is true &&
                        typeof(RouteGlobeWebView).GetField("_statePushQueued", flags)?.GetValue(injectingGlobe) is false) break;
                }
                if (injectingGlobe is null || injectingCore is null ||
                    typeof(RouteGlobeWebView).GetField("_pageReady", flags)?.GetValue(injectingGlobe) is not true)
                    throw new TimeoutException("Injection needs a ready native map.");
                var delayed = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
                var blocked = false;
                var executorCore = injectingCore;
                var executorGlobe = injectingGlobe;
                injectingGlobe.StateScriptExecutor = script =>
                {
                    if (!blocked)
                    {
                        blocked = true;
                        if (simulateTimeout)
                        {
                            // Delay the real Chromium invocation only in this owned fixture.
                            // New adapter generations use the unmodified production executor.
                            executorGlobe.StateScriptExecutor = null;
                            Console.WriteLine("QA real native script invocation deliberately delayed beyond deadline.");
                            return executorCore.ExecuteScriptAsync("{const qaDeadline=performance.now()+3000;while(performance.now()<qaDeadline){}};" + script);
                        }
                        Console.WriteLine("QA state push backend response deliberately withheld.");
                        return delayed.Task;
                    }
                    return executorCore.ExecuteScriptAsync(script);
                };
                if (simulateTimeout)
                {
                    var generation = (int)typeof(RouteGlobeWebView).GetField("_webViewGeneration", flags)!.GetValue(injectingGlobe)!;
                    injectingGlobe.ResumeHost();
                    await Task.Delay(3700);
                    var recoveredGeneration = (int)typeof(RouteGlobeWebView).GetField("_webViewGeneration", flags)!.GetValue(injectingGlobe)!;
                    if (recoveredGeneration <= generation)
                        throw new InvalidOperationException("Native script deadline did not replace the stalled adapter.");
                    Console.WriteLine($"QA native script deadline recovery: generation {generation} -> {recoveredGeneration}");
                }
            }
            var hiddenDelayIndex = Array.IndexOf(Environment.GetCommandLineArgs(), "--hidden-delay-ms");
            var hiddenDelayMs = hiddenDelayIndex >= 0 ? int.Parse(Environment.GetCommandLineArgs()[hiddenDelayIndex + 1], CultureInfo.InvariantCulture) : 150;
            foreach (var routeId in new[] { "auto", "de-vless", "fi-trojan", "it-vless", "ru-vless", "ru-de",
                "nl-vless", "nl-trojan", "nl-hysteria2", "auto", "de-trojan", "fi-vless", "auto" })
            {
                Routes();
                await Task.Delay(results.Count == 0 ? hiddenDelayMs : 150);
                var beforeGlobe = typeof(MainWindow).GetField("_routeGlobe", flags)?.GetValue(window) as RouteGlobeWebView;
                var beforeCore = beforeGlobe is null ? null : typeof(RouteGlobeWebView).GetField("_coreWebView2", flags)?.GetValue(beforeGlobe) as CoreWebView2;
                var beforeFrames = beforeCore is null ? 0 : (await ReadAtlasSnapshotAsync(beforeCore)).RenderCount;
                Button? button;
                if (routeId is "auto" or "ru-de")
                {
                    var title = routeId == "auto" ? "автоподбор" : "LTE · белые списки";
                    button = window.FindControl<Grid>("PageHost")!.GetVisualDescendants().OfType<Button>()
                        .FirstOrDefault(b => b.IsEffectivelyVisible && b.GetVisualDescendants().OfType<TextBlock>()
                            .Any(t => string.Equals(t.Text, title, StringComparison.OrdinalIgnoreCase)));
                }
                else
                {
                    button = FindButton("RouteOption-" + routeId);
                    if (button is null)
                    {
                        var country = routeId.Split('-')[0].ToUpperInvariant();
                        (FindButton("RouteCountry-" + country) ?? throw new InvalidOperationException("Missing country " + country))
                            .RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                        await Task.Delay(100);
                        button = FindButton("RouteOption-" + routeId);
                    }
                }
                if (button is null) throw new InvalidOperationException("Missing actual route button " + routeId);
                Console.WriteLine("QA route selection start: " + routeId);
                var click = Stopwatch.StartNew();
                button.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                var clickMs = click.Elapsed.TotalMilliseconds;
                var ready = Stopwatch.StartNew();
                string? lastSample = null;
                CoreWebView2? core = null;
                RouteGlobeWebView? globe = null;
                var rendered = false;
                while (ready.Elapsed < TimeSpan.FromSeconds(8))
                {
                    await Task.Delay(40);
                    globe = typeof(MainWindow).GetField("_routeGlobe", flags)?.GetValue(window) as RouteGlobeWebView;
                    core = globe is null ? null : typeof(RouteGlobeWebView).GetField("_coreWebView2", flags)?.GetValue(globe) as CoreWebView2;
                    if (core is null) continue;
                    lastSample = await core.ExecuteScriptAsync("(()=>{const a=window.deyttMapAtlas;return {ready:!!a,route:a?.route,hostActive:a?.hostActive,visible:a?.visible,frames:a?.renderCount,frame:a?.frame,hidden:document.hidden,errors:window.__qaRouteErrors||[]};})()")
                        .WaitAsync(TimeSpan.FromSeconds(3));
                    using var sample = JsonDocument.Parse(lastSample);
                    var s = sample.RootElement;
                    if (s.GetProperty("errors").GetArrayLength() != 0)
                        throw new InvalidOperationException("Home JavaScript error: " + s.GetProperty("errors"));
                    var expected = RouteGlobeWebView.RouteKeyFor(routeId);
                    if (s.GetProperty("ready").GetBoolean() && s.GetProperty("route").GetString() == expected &&
                        s.GetProperty("hostActive").GetBoolean() && s.GetProperty("frames").GetInt64() > beforeFrames)
                    {
                        rendered = true;
                        break;
                    }
                }
                if (!rendered || globe is null || core is null)
                    throw new TimeoutException("Home did not render the selected route " + routeId + ": " + lastSample);
                var nativeHandle = (IntPtr)(typeof(RouteGlobeWebView).GetField("_nativeMapWindowHandle", flags)?.GetValue(globe) ?? IntPtr.Zero);
                var nativeVisible = nativeHandle != IntPtr.Zero && IsWindowVisible(nativeHandle);
                if (!globe.IsEffectivelyVisible || !nativeVisible)
                    throw new InvalidOperationException("Home browser rendered internally but its native window is hidden.");
                if (retainedCore is not null && (!ReferenceEquals(core, retainedCore) || !ReferenceEquals(globe, retainedGlobe)))
                    throw new InvalidOperationException("Route selection replaced the retained map/core.");
                retainedCore = core;
                retainedGlobe = globe;
                await core.ExecuteScriptAsync("if(!window.__qaRouteErrors){window.__qaRouteErrors=[];addEventListener('error',e=>window.__qaRouteErrors.push(e.message));}");
                using var preview = await globe.CapturePreviewAsync();
                if (preview is null) throw new InvalidOperationException("Selected Home has no native bitmap.");
                if (options.CaptureHomePreview is { Length: > 0 } capture)
                {
                    await using var output = File.Create(capture);
                    preview.Save(output, PngBitmapEncoderOptions.Default);
                }
                var result = new { routeId, clickMs, homeReadyMs = ready.Elapsed.TotalMilliseconds, nativeVisible,
                    maxUiHeartbeatMs = maxHeartbeatMs, snapshot = lastSample,
                    revision = typeof(RouteGlobeWebView).GetField("_stateRevision", flags)?.GetValue(globe),
                    appliedRevision = typeof(RouteGlobeWebView).GetField("_appliedRevision", flags)?.GetValue(globe),
                    queued = typeof(RouteGlobeWebView).GetField("_statePushQueued", flags)?.GetValue(globe) };
                results.Add(result);
                Console.WriteLine("QA route selection result: " + JsonSerializer.Serialize(result));
            }
            Console.WriteLine("QA route selection summary: " + JsonSerializer.Serialize(new { passed = true, choices = results.Count, maxUiHeartbeatMs = maxHeartbeatMs }));
        }
        catch (Exception error)
        {
            Program.QaExitCode = 1;
            Console.Error.WriteLine("QA route selection failed: " + error);
        }
        finally
        {
            heartbeat.Stop();
            window.Close();
        }
    }

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    [return: System.Runtime.InteropServices.MarshalAs(System.Runtime.InteropServices.UnmanagedType.Bool)]
    private static extern bool IsWindowVisible(IntPtr window);

    private static async Task RunNavigationQaAsync(MainWindow window, QaOptions options)
    {
        try
        {
            var showTab = typeof(MainWindow).GetMethod("ShowTab", BindingFlags.Instance | BindingFlags.NonPublic)
                ?? throw new MissingMethodException(typeof(MainWindow).FullName, "ShowTab");
            var tabType = showTab.GetParameters()[0].ParameterType;
            void Navigate(string tab) => showTab.Invoke(window, [Enum.Parse(tabType, tab), true]);
            Navigate("Home");

            var deadline = DateTimeOffset.UtcNow.AddSeconds(30);
            RouteGlobeWebView? globe = null;
            CoreWebView2? core = null;
            while (DateTimeOffset.UtcNow < deadline)
            {
                globe = typeof(MainWindow).GetField("_routeGlobe", BindingFlags.Instance | BindingFlags.NonPublic)
                    ?.GetValue(window) as RouteGlobeWebView;
                core = globe is null ? null : typeof(RouteGlobeWebView)
                    .GetField("_coreWebView2", BindingFlags.Instance | BindingFlags.NonPublic)
                    ?.GetValue(globe) as CoreWebView2;
                if (globe is not null && core is not null)
                {
                    try
                    {
                        var ready = await core.ExecuteScriptAsync("Boolean(window.deyttMapAtlas && window.deyttMapAtlas.canvas)");
                        if (JsonSerializer.Deserialize<bool>(ready))
                            break;
                    }
                    catch (Exception) { }
                }

                await Task.Delay(150);
            }

            if (globe is null || core is null || DateTimeOffset.UtcNow >= deadline)
                throw new TimeoutException("QA navigation could not find a ready native map WebView within 30 seconds.");

            Navigate("Home");
            await Task.Delay(50);

            var heartbeat = new Avalonia.Threading.DispatcherTimer
            {
                Interval = TimeSpan.FromMilliseconds(12),
            };
            var lastHeartbeat = Stopwatch.GetTimestamp();
            double maxHeartbeatDelayMs = 0;
            heartbeat.Tick += (_, _) =>
            {
                var now = Stopwatch.GetTimestamp();
                var delay = Stopwatch.GetElapsedTime(lastHeartbeat, now).TotalMilliseconds;
                maxHeartbeatDelayMs = Math.Max(maxHeartbeatDelayMs, delay);
                lastHeartbeat = now;
            };
            heartbeat.Start();

            var cycleResults = new List<object>();
            try
            {
                for (var cycle = 1; cycle <= 5 && options.VerifyNavigation; cycle++)
                {
                    var routesCallMs = Measure(() => Navigate("Routes"));
                    var hiddenBefore = await ReadAtlasSnapshotAsync(core);
                    for (var attempt = 0; hiddenBefore.HostActive is true && attempt < 40; attempt++)
                    {
                        await Task.Delay(15);
                        hiddenBefore = await ReadAtlasSnapshotAsync(core);
                    }
                    var hiddenWatch = Stopwatch.StartNew();
                    await Task.Delay(300);
                    var hiddenAfter = await ReadAtlasSnapshotAsync(core);
                    var hiddenDurationMs = hiddenWatch.Elapsed.TotalMilliseconds;
                    var hiddenHost = hiddenAfter.HostActive;
                    if (hiddenHost is true)
                        throw new InvalidOperationException("The atlas host-active state remained active on the Routes page.");
                    if (hiddenHost is false)
                        Console.WriteLine($"QA navigation invariant host-paused cycle={cycle}: true");

                    var homeCallMs = Measure(() => Navigate("Home"));
                    var resumeWatch = Stopwatch.StartNew();
                    var resumed = false;
                    for (var attempt = 0; attempt < 40; attempt++)
                    {
                        await Task.Delay(15);
                        var sample = await ReadAtlasSnapshotAsync(core);
                        if (sample.RenderCount > hiddenAfter.RenderCount)
                        {
                            resumed = true;
                            break;
                        }
                    }

                    var afterResume = await ReadAtlasSnapshotAsync(core);
                    if (!ReferenceEquals(globe, typeof(MainWindow).GetField("_routeGlobe",
                            BindingFlags.Instance | BindingFlags.NonPublic)?.GetValue(window)) ||
                        !ReferenceEquals(core, typeof(RouteGlobeWebView).GetField("_coreWebView2",
                            BindingFlags.Instance | BindingFlags.NonPublic)?.GetValue(globe)))
                        throw new InvalidOperationException("Navigation replaced the retained map or WebView core instance.");
                    if (hiddenHost is false && afterResume.HostActive is not true)
                        throw new InvalidOperationException("The atlas host-active state did not resume on the Home page.");
                    if (!resumed)
                        throw new InvalidOperationException("The atlas render loop did not resume after returning Home.");

                    var result = new
                    {
                        cycle,
                        routesShowTabMs = routesCallMs,
                        homeShowTabMs = homeCallMs,
                        hiddenDurationMs,
                        hiddenBeforeRenderCount = hiddenBefore.RenderCount,
                        hiddenAfterRenderCount = hiddenAfter.RenderCount,
                        hiddenRenderCountDelta = hiddenAfter.RenderCount - hiddenBefore.RenderCount,
                        hiddenBeforeHostActive = hiddenBefore.HostActive,
                        hiddenAfterHostActive = hiddenAfter.HostActive,
                        hiddenBeforeActiveDomAnimations = hiddenBefore.ActiveDomAnimations,
                        hiddenAfterActiveDomAnimations = hiddenAfter.ActiveDomAnimations,
                        hostStateSupported = hiddenAfter.HostActive.HasValue,
                        hiddenHostActive = hiddenAfter.HostActive,
                        homeHostActive = afterResume.HostActive,
                        homeResumeMs = resumeWatch.Elapsed.TotalMilliseconds,
                        homeResumeStartedAtRenderCount = hiddenAfter.RenderCount,
                        homeResumeRenderCount = afterResume.RenderCount,
                        activeDomAnimations = afterResume.ActiveDomAnimations,
                        maxUiHeartbeatDelayMs = maxHeartbeatDelayMs,
                    };
                    cycleResults.Add(result);
                    Console.WriteLine("QA navigation cycle: " + JsonSerializer.Serialize(result));
                }
            }
            finally
            {
                heartbeat.Stop();
            }

            if (options.CaptureMotion)
                await CaptureHomeMotionAsync(window, core, options);

            if (options.VerifyZoom)
                await RunZoomQaAsync(window, core, options);

            Console.WriteLine("QA navigation summary: " + JsonSerializer.Serialize(new
            {
                cycles = cycleResults,
                sameGlobeRetained = true,
                sameWebViewCoreRetained = true,
                maxUiHeartbeatDelayMs = maxHeartbeatDelayMs,
            }));
        }
        catch (Exception exception)
        {
            Console.Error.WriteLine($"QA navigation failed: {exception.GetType().Name}: {exception.Message}");
            Program.QaExitCode = 1;
        }
        finally
        {
            window.Close();
        }

        static double Measure(Action action)
        {
            var stopwatch = Stopwatch.StartNew();
            action();
            return stopwatch.Elapsed.TotalMilliseconds;
        }
    }

    private sealed record AtlasSnapshot(long RenderCount, bool? HostActive, int ActiveDomAnimations);

    private static async Task RunZoomQaAsync(MainWindow window, CoreWebView2 core, QaOptions options)
    {
        const string snapshotScript = """
            (()=>{const a=window.deyttMapAtlas,wrap=a?.root.querySelector('.network-atlas__canvas-wrap'),card=a?.root.querySelector('.home-compact'),content=card?.querySelector('.home-compact-content'),layer=a?.staticCanvas;
            if(!a||!wrap||!card||!content||!layer)return JSON.stringify({ready:false});
            const w=wrap.getBoundingClientRect(),b=layer.getBoundingClientRect(),c=card.getBoundingClientRect(),i=content.getBoundingClientRect();
            const cx=w.left+a.centerX,cy=w.top+a.centerY,r=a.radius*a.zoom;
            const left=Math.max(w.left,cx-r),right=Math.min(w.right,cx+r),top=Math.max(w.top,cy-r),bottom=Math.min(w.bottom,cy+r);
            const intersects=left<right&&top<bottom;
            const style=getComputedStyle(card),matrix=new DOMMatrixReadOnly(getComputedStyle(layer).transform==='none'?'matrix(1,0,0,1,0,0)':getComputedStyle(layer).transform);
            return JSON.stringify({ready:true,zoom:a.zoom,targetZoom:a.targetZoom,cameraMoving:a.cameraMoving,renderCount:a.renderCount,
              hostActive:typeof a.hostActive==='boolean'?a.hostActive:null,reduceMotion:a.reducedMotion?.matches===true,
              wrap:{left:w.left,top:w.top,right:w.right,bottom:w.bottom},layer:{left:b.left,top:b.top,right:b.right,bottom:b.bottom},
              sphere:{left,right,top,bottom,intersects},coverage:!intersects||(b.left<=left+1&&b.top<=top+1&&b.right>=right-1&&b.bottom>=bottom-1),
              scaleX:matrix.a,scaleY:matrix.d,compact:{ariaHidden:card.getAttribute('aria-hidden'),display:style.display,visibility:style.visibility,opacity:Number(style.opacity),
                contentLeft:i.left-c.left,contentTop:i.top-c.top,contentRight:c.right-i.right,contentBottom:c.bottom-i.bottom,
                contentWidth:i.width,cardWidth:c.width}});})()
            """;
        var failures = new List<object>();
        var samples = new List<object>();
        double[]? compactReferenceBounds = null;
        var captureDirectory = options.CaptureMotionPath is { Length: > 0 } requested
            ? Path.GetFullPath(requested)
            : null;
        if (captureDirectory is not null)
            Directory.CreateDirectory(captureDirectory);

        if (Environment.GetCommandLineArgs().Contains("--reduce-motion", StringComparer.Ordinal))
            await core.ExecuteScriptAsync("window.deyttSetMapReducedMotion?.(true)");

        await core.ExecuteScriptAsync("""
            (()=>{const a=window.deyttMapAtlas;if(!a||a.__qaZoomOriginalRender)return false;
            const original=a.render,monitor={framesChecked:0,maxUncoveredPx:0,firstUncovered:[],maxPanelAxisError:0,firstPanelSqueeze:[],errors:[]};
            a.__qaZoomMonitor=monitor;a.__qaZoomOriginalRender=original;
             a.render=function(time){const result=original.apply(this,arguments);try{const m=a.__qaZoomMonitor,w=a.root.querySelector('.network-atlas__canvas-wrap').getBoundingClientRect(),b=a.staticCanvas.getBoundingClientRect(),p=a.root.querySelector('.home-panel'),cx=w.left+a.centerX,cy=w.top+a.centerY,r=a.radius*a.zoom,left=Math.max(w.left,cx-r),right=Math.min(w.right,cx+r),top=Math.max(w.top,cy-r),bottom=Math.min(w.bottom,cy+r),intersects=left<right&&top<bottom,gap=intersects?Math.max(0,b.left-left, b.top-top, right-b.right, bottom-b.bottom):0;
               const pm=new DOMMatrixReadOnly(getComputedStyle(p).transform==='none'?'matrix(1,0,0,1,0,0)':getComputedStyle(p).transform),sx=Math.hypot(pm.m11,pm.m12),sy=Math.hypot(pm.m21,pm.m22),axisError=Math.max(Math.abs(sx-1),Math.abs(sy-1));
              m.framesChecked++;m.maxUncoveredPx=Math.max(m.maxUncoveredPx,gap);if(gap>1&&m.firstUncovered.length<3)m.firstUncovered.push({frame:m.framesChecked,zoom:a.zoom,targetZoom:a.targetZoom,gapPx:gap,sphere:{left,right,top,bottom},layer:{left:b.left,top:b.top,right:b.right,bottom:b.bottom}});
              m.maxPanelAxisError=Math.max(m.maxPanelAxisError,axisError);if(axisError>.01&&m.firstPanelSqueeze.length<3)m.firstPanelSqueeze.push({frame:m.framesChecked,zoom:a.zoom,scaleX:sx,scaleY:sy,error:axisError});
            }catch(error){const m=a.__qaZoomMonitor;if(m&&m.errors.length<3)m.errors.push({frame:m.framesChecked,error:String(error)});}return result;};return true;})()
            """);

        async Task<JsonDocument> ReadSnapshotAsync()
        {
            var encoded = await core.ExecuteScriptAsync(snapshotScript);
            var json = JsonSerializer.Deserialize<string>(encoded) ?? "{}";
            return JsonDocument.Parse(json);
        }

        async Task<bool> WaitSettledAsync(double expectedZoom)
        {
            var until = DateTimeOffset.UtcNow.AddSeconds(4);
            while (DateTimeOffset.UtcNow < until)
            {
                using var doc = await ReadSnapshotAsync();
                var root = doc.RootElement;
                if (root.TryGetProperty("ready", out var ready) && ready.GetBoolean() &&
                    Math.Abs(root.GetProperty("zoom").GetDouble() - expectedZoom) < .003 &&
                    Math.Abs(root.GetProperty("targetZoom").GetDouble() - expectedZoom) < .003 &&
                    !root.GetProperty("cameraMoving").GetBoolean() &&
                    Math.Abs(root.GetProperty("scaleX").GetDouble() - 1) < .003 &&
                    Math.Abs(root.GetProperty("scaleY").GetDouble() - 1) < .003)
                    return true;
                await Task.Delay(20);
            }
            return false;
        }

        async Task MoveAsync(string stage, double target, bool reverseMidGesture = false)
        {
            var targetText = target.ToString("0.###", CultureInfo.InvariantCulture);
            await core.ExecuteScriptAsync($"(()=>{{const a=window.deyttMapAtlas;if(!a)return false;a.targetZoom={targetText};a.start();return true;}})()");
            await Task.Delay(reverseMidGesture ? 95 : 120);
            if (reverseMidGesture)
            {
                using var during = await ReadSnapshotAsync();
                samples.Add(new { stage = stage + ".before-reversal", snapshot = during.RootElement.Clone() });
                CheckSnapshot(stage + ".before-reversal", during.RootElement, settled: false);
            }

            if (captureDirectory is not null)
            {
                var globe = typeof(MainWindow).GetField("_routeGlobe", BindingFlags.Instance | BindingFlags.NonPublic)
                    ?.GetValue(window) as RouteGlobeWebView;
                using var preview = globe is null ? null : await globe.CapturePreviewAsync();
                if (preview is null)
                    failures.Add(new { stage, invariant = "native screenshot captured", actual = "CapturePreviewAsync returned null" });
                else
                {
                    var capturePath = Path.Combine(captureDirectory, $"zoom-{stage.Replace('.', '-')}.png");
                    await using var output = File.Create(capturePath);
                    preview.Save(output, PngBitmapEncoderOptions.Default);
                    Console.WriteLine($"QA zoom frame: {capturePath}");
                }
            }

            using (var during = await ReadSnapshotAsync())
            {
                samples.Add(new { stage = stage + ".moving", snapshot = during.RootElement.Clone() });
                CheckSnapshot(stage + ".moving", during.RootElement, settled: false);
            }

            if (reverseMidGesture)
            {
                target = target == 3 ? 1 : 3;
                targetText = target.ToString("0.###", CultureInfo.InvariantCulture);
                await core.ExecuteScriptAsync($"(()=>{{const a=window.deyttMapAtlas;if(!a)return false;a.targetZoom={targetText};a.start();return true;}})()");
                await Task.Delay(95);
                using var afterReverse = await ReadSnapshotAsync();
                samples.Add(new { stage = stage + ".after-reversal", snapshot = afterReverse.RootElement.Clone() });
                CheckSnapshot(stage + ".after-reversal", afterReverse.RootElement, settled: false);
            }

            var settled = await WaitSettledAsync(target);
            if (!settled)
                failures.Add(new { stage, invariant = "zoom, camera, and bitmap transform settle at requested target", targetZoom = target });
            await Task.Delay(160); // allow the panel opacity transition to finish too
            using var final = await ReadSnapshotAsync();
            samples.Add(new { stage = stage + ".settled", snapshot = final.RootElement.Clone() });
            CheckSnapshot(stage + ".settled", final.RootElement, settled: true);
            if (Math.Abs(final.RootElement.GetProperty("zoom").GetDouble() - target) >= .003 ||
                Math.Abs(final.RootElement.GetProperty("targetZoom").GetDouble() - target) >= .003)
                failures.Add(new { stage, invariant = "final zoom equals requested target", targetZoom = target,
                    zoom = final.RootElement.GetProperty("zoom").GetDouble(), targetZoomActual = final.RootElement.GetProperty("targetZoom").GetDouble() });
            if (Math.Abs(final.RootElement.GetProperty("scaleX").GetDouble() - 1) >= .003 ||
                Math.Abs(final.RootElement.GetProperty("scaleY").GetDouble() - 1) >= .003)
                failures.Add(new { stage, invariant = "cached bitmap scale settles to 1" });
        }

        void CheckSnapshot(string stage, JsonElement snapshot, bool settled)
        {
            if (!snapshot.TryGetProperty("ready", out var ready) || !ready.GetBoolean())
            {
                failures.Add(new { stage, invariant = "zoom DOM ready" });
                return;
            }
            if (!snapshot.GetProperty("coverage").GetBoolean())
                failures.Add(new { stage, invariant = "cached bitmap covers visible analytic sphere bounds", sphere = snapshot.GetProperty("sphere").Clone(), layer = snapshot.GetProperty("layer").Clone() });

            var compact = snapshot.GetProperty("compact");
            if (settled && compact.GetProperty("ariaHidden").GetString() == "true" &&
                compact.GetProperty("display").GetString() != "none" && compact.GetProperty("visibility").GetString() != "hidden" &&
                compact.GetProperty("opacity").GetDouble() > .001)
                failures.Add(new { stage, invariant = "aria-hidden compact card has no visible background", opacity = compact.GetProperty("opacity").GetDouble() });
            if (settled && compact.GetProperty("ariaHidden").GetString() == "false")
            {
                var edges = new[] { "contentLeft", "contentTop", "contentRight", "contentBottom" };
                foreach (var edge in edges)
                    if (compact.GetProperty(edge).GetDouble() < -1)
                        failures.Add(new { stage, invariant = "compact content stays within card bounds", edge, value = compact.GetProperty(edge).GetDouble() });
                if (snapshot.GetProperty("targetZoom").GetDouble() >= 2.9)
                {
                    var bounds = edges.Select(edge => compact.GetProperty(edge).GetDouble()).ToArray();
                    if (compactReferenceBounds is null)
                        compactReferenceBounds = bounds;
                    else if (bounds.Where((value, index) => Math.Abs(value - compactReferenceBounds[index]) > 1).Any())
                        failures.Add(new { stage, invariant = "compact content relative bounds remain stable at deep zoom", expected = compactReferenceBounds, actual = bounds });
                }
            }
        }

        try
        {
            await core.ExecuteScriptAsync("(()=>{const a=window.deyttMapAtlas;if(!a)return false;a.targetZoom=1;a.start();return true;})()");
            if (!await WaitSettledAsync(1))
                failures.Add(new { stage = "initial", invariant = "zoom settles at 1 before probe" });

            foreach (var (stage, target) in new[]
            {
                ("cycle1-1_4", 1.4), ("cycle1-3", 3.0), ("cycle1-1", 1.0),
                ("cycle2-1_4", 1.4), ("cycle2-3", 3.0), ("cycle2-1", 1.0),
            })
                await MoveAsync(stage, target);

            await MoveAsync("rapid-zoom-in-reverse", 3.0, reverseMidGesture: true);
            await MoveAsync("rapid-prep-3", 3.0);
            await MoveAsync("rapid-zoom-out-reverse", 1.0, reverseMidGesture: true);
            var originalWidth = window.Width;
            var originalHeight = window.Height;
            try
            {
                foreach (var zoom in new[] { 1.0, 3.0 })
                {
                    await MoveAsync("resize-prep-" + zoom, zoom);
                    foreach (var size in new[] { (1100.0, 760.0), (1440.0, 900.0), (originalWidth, originalHeight) })
                    {
                        window.Width = size.Item1;
                        window.Height = size.Item2;
                        await Task.Delay(400);
                        var settled = await WaitSettledAsync(zoom);
                        using var resized = await ReadSnapshotAsync();
                        var stage = $"resize-{zoom}-{size.Item1}x{size.Item2}";
                        samples.Add(new { stage, snapshot = resized.RootElement.Clone() });
                        CheckSnapshot(stage, resized.RootElement, settled);
                        if (!settled)
                            failures.Add(new { stage, invariant = "camera settles after native window resize" });
                    }
                }
            }
            finally
            {
                window.Width = originalWidth;
                window.Height = originalHeight;
            }
            var motionPreference = await core.ExecuteScriptAsync("""
                (()=>{const wasReduced=window.deyttMapReducedMotion;
                window.deyttReplayHomeEntrance();
                const active=()=>document.getAnimations().filter(x=>x.constructor.name==='Animation'&&(x.playState==='running'||x.playState==='pending')).length;
                const before=active();window.deyttSetMapReducedMotion(true);const after=active();
                window.deyttSetMapReducedMotion(wasReduced);return JSON.stringify({before,after});})()
                """);
            using var motionResult = JsonDocument.Parse(JsonSerializer.Deserialize<string>(motionPreference) ?? "{}");
            samples.Add(new { stage = "reduce-motion-interruption", snapshot = motionResult.RootElement.Clone() });
            if (motionResult.RootElement.GetProperty("after").GetInt32() != 0)
                failures.Add(new { stage = "reduce-motion-interruption", invariant = "app motion preference cancels active WAAPI entrance animations" });
        }
        catch (Exception exception)
        {
            failures.Add(new { stage = "runner", invariant = "zoom probe completed", error = exception.GetType().Name + ": " + exception.Message });
        }

        string monitorReport = "{}";
        try
        {
            monitorReport = JsonSerializer.Deserialize<string>(await core.ExecuteScriptAsync("""
                (()=>{const m=window.deyttMapAtlas?.__qaZoomMonitor;if(!m)return JSON.stringify({framesChecked:0,maxUncoveredPx:0,firstUncovered:[],maxPanelAxisError:0,firstPanelSqueeze:[],errors:[]});return JSON.stringify(m);})()
                """)) ?? "{}";
        }
        catch (Exception exception)
        {
            failures.Add(new { stage = "monitor", invariant = "render monitor restored and report read", error = exception.GetType().Name + ": " + exception.Message });
        }
        finally
        {
            try
            {
                await core.ExecuteScriptAsync("(()=>{const a=window.deyttMapAtlas;if(a?.__qaZoomOriginalRender){a.render=a.__qaZoomOriginalRender;a.__qaZoomOriginalRender=null;}return true;})()");
            }
            catch (Exception exception)
            {
                failures.Add(new { stage = "monitor", invariant = "original render method restored", error = exception.GetType().Name + ": " + exception.Message });
            }
        }
        JsonElement monitorElement;
        try
        {
            using var monitor = JsonDocument.Parse(monitorReport);
            var metrics = monitor.RootElement;
            monitorElement = metrics.Clone();
            if (metrics.GetProperty("framesChecked").GetInt32() == 0)
                failures.Add(new { stage = "continuous", invariant = "at least one real render frame sampled" });
            if (metrics.GetProperty("maxUncoveredPx").GetDouble() > 1)
                failures.Add(new { stage = "continuous", invariant = "cached bitmap covers visible analytic sphere bounds on every render frame",
                    maxUncoveredPx = metrics.GetProperty("maxUncoveredPx").GetDouble(), evidence = metrics.GetProperty("firstUncovered").Clone() });
            if (metrics.GetProperty("maxPanelAxisError").GetDouble() > .01)
                failures.Add(new { stage = "continuous", invariant = "home panel transform axes remain unit scale",
                    maxAxisError = metrics.GetProperty("maxPanelAxisError").GetDouble(), evidence = metrics.GetProperty("firstPanelSqueeze").Clone() });
            if (metrics.GetProperty("errors").GetArrayLength() != 0)
                failures.Add(new { stage = "continuous", invariant = "render-frame geometry sampling completed", evidence = metrics.GetProperty("errors").Clone() });
        }
        catch (Exception exception)
        {
            monitorElement = JsonDocument.Parse("{}").RootElement.Clone();
            failures.Add(new { stage = "monitor", invariant = "continuous metrics parse", error = exception.GetType().Name + ": " + exception.Message });
        }

        var report = new { passed = failures.Count == 0, reduceMotion = Environment.GetCommandLineArgs().Contains("--reduce-motion", StringComparer.Ordinal), monitor = monitorElement, samples, failures };
        Console.WriteLine("QA zoom report: " + JsonSerializer.Serialize(report));
        if (failures.Count != 0)
            Program.QaExitCode = 1;
    }

    private static async Task<AtlasSnapshot> ReadAtlasSnapshotAsync(CoreWebView2 core)
    {
        const string script = """
            (()=>{const a=window.deyttMapAtlas;if(!a)return JSON.stringify({ready:false});
            const host=typeof a.hostActive==='boolean'?a.hostActive:null;
            return JSON.stringify({ready:true,renderCount:Number(a.renderCount)||0,hostActive:host,
              activeDomAnimations:document.getAnimations().filter(x=>x.playState==='running'||x.playState==='pending').length});})()
            """;
        var json = await core.ExecuteScriptAsync(script);
        using var document = JsonDocument.Parse(json);
        var root = JsonDocument.Parse(document.RootElement.GetString() ?? "{}");
        using (root)
        {
            var value = root.RootElement;
            if (!value.TryGetProperty("ready", out var ready) || !ready.GetBoolean())
                return new AtlasSnapshot(0, null, 0);
            bool? hostActive = value.TryGetProperty("hostActive", out var host) && host.ValueKind is JsonValueKind.True or JsonValueKind.False
                ? host.GetBoolean()
                : null;
            return new AtlasSnapshot(value.GetProperty("renderCount").GetInt64(), hostActive,
                value.GetProperty("activeDomAnimations").GetInt32());
        }
    }

    private static async Task CaptureHomeMotionAsync(MainWindow window, CoreWebView2 core, QaOptions options)
    {
        var showTab = typeof(MainWindow).GetMethod("ShowTab", BindingFlags.Instance | BindingFlags.NonPublic);
        if (showTab is not null)
            showTab.Invoke(window, [Enum.Parse(showTab.GetParameters()[0].ParameterType, "Home"), true]);

        var replayResult = await core.ExecuteScriptAsync("""
            (()=>{if(typeof window.deyttReplayHomeEntrance==='function'){window.deyttReplayHomeEntrance();return 'home-entrance';}
            if(window.deyttMapAtlas){window.deyttSetMapRoute?.('auto');window.deyttMapAtlas.autoSelecting=true;
            window.deyttMapAtlas.markMapDirty();window.deyttMapAtlas.start();return 'synthetic-auto-select';}return 'unavailable';})()
            """);
        Console.WriteLine("QA motion trigger: " + (JsonSerializer.Deserialize<string>(replayResult) ?? replayResult));

        var directory = options.CaptureMotionPath is { Length: > 0 } requested
            ? Path.GetFullPath(requested)
            : Path.Combine(Path.GetTempPath(), $"DeyttConnect-QA-Motion-{Environment.ProcessId}");
        Directory.CreateDirectory(directory);
        for (var frame = 1; frame <= 5; frame++)
        {
            await Task.Delay(120);
            var globe = typeof(MainWindow).GetField("_routeGlobe", BindingFlags.Instance | BindingFlags.NonPublic)
                ?.GetValue(window) as RouteGlobeWebView;
            var preview = globe is null ? null : await globe.CapturePreviewAsync();
            if (preview is null)
                continue;
            var path = Path.Combine(directory, $"motion-{frame:00}.png");
            using (preview)
            {
                await using var output = File.Create(path);
                preview.Save(output, PngBitmapEncoderOptions.Default);
            }
            Console.WriteLine($"QA motion frame {frame}: {path}");
            Console.WriteLine("QA motion DOM animations: " + await core.ExecuteScriptAsync(
                "document.getAnimations().filter(x=>x.playState==='running'||x.playState==='pending').length"));
        }

        await core.ExecuteScriptAsync("if(window.deyttMapAtlas){window.deyttMapAtlas.autoSelecting=false;window.deyttMapAtlas.markMapDirty();window.deyttMapAtlas.start();}");
    }

    private sealed record QaOptions(bool SignedIn, string State, string Tab, string Theme,
        string? ProbeStage, int Width, int Height, string? CaptureHomePreview, string Route, bool FlatPanel,
        double? Zoom, bool Immersive, bool MeasureFrames, bool InspectTrafficLine, bool WithOrigin,
        bool RefreshMetrics, string? HoverSelector, bool MotionSample, bool VerifyInteractions, string Language,
        bool SubscriptionWarning, bool FocusPanel, bool VerifyNavigation, bool VerifyRouteSelection, bool VerifyZoom, bool CaptureMotion,
        string? CaptureMotionPath)
    {
        public static QaOptions Parse(string[] args)
        {
            var signedIn = args.Contains("--signed-in", StringComparer.Ordinal);
            var state = Value(args, "--state") ?? "disconnected";
            if (state is not ("disconnected" or "connected" or "connecting" or "error"))
                throw new ArgumentException("--state must be disconnected, connected, connecting, or error.");
            var tab = Value(args, "--tab") ?? "home";
            if (tab is not ("home" or "routes" or "profile" or "settings"))
                throw new ArgumentException("--tab must be home, routes, profile, or settings.");
            var theme = Value(args, "--theme") ?? "system";
            if (theme is not ("system" or "light" or "dark"))
                throw new ArgumentException("--theme must be system, light, or dark.");
            var probeStage = Value(args, "--probe-stage");
            if (probeStage is not (null or "latency" or "retry" or "waiting_speed" or "download" or "error" or "cancelled" or "complete"))
                throw new ArgumentException("--probe-stage must be latency, retry, waiting_speed, download, error, cancelled, or complete.");
            var route = Value(args, "--route") ?? "nl-hysteria2";
            if (route is not ("auto" or "ru-de" or "nl-vless" or "nl-trojan" or "nl-hysteria2" or "nl-awg31" or
                "de-vless" or "de-trojan" or "de-hysteria2" or "ru-vless" or "ru-trojan" or "ru-hysteria2" or
                "fi-vless" or "fi-trojan"))
                throw new ArgumentException("--route must name a route supplied by the QA fixture.");
            var width = Dimension(args, "--width", 1240, 520);
            var height = Dimension(args, "--height", 820, 420);
            var captureHomePreview = Value(args, "--capture-home-preview") ?? Value(args, "--capture-page");
            var captureMotionIndex = Array.IndexOf(args, "--capture-motion");
            var captureMotionPath = captureMotionIndex >= 0 && captureMotionIndex + 1 < args.Length &&
                !args[captureMotionIndex + 1].StartsWith("--", StringComparison.Ordinal)
                    ? args[captureMotionIndex + 1]
                    : null;
            double? zoom = null;
            var zoomText = Value(args, "--zoom");
            if (zoomText is not null)
            {
                if (!double.TryParse(zoomText, NumberStyles.Float, CultureInfo.InvariantCulture, out var parsedZoom) || parsedZoom is < .85 or > 3)
                    throw new ArgumentException("--zoom must be a number between 0.85 and 3.");
                zoom = parsedZoom;
            }
            return new QaOptions(signedIn, state, tab, theme, probeStage, width, height, captureHomePreview, route,
                args.Contains("--flat-panel", StringComparer.Ordinal), zoom,
                args.Contains("--immersive", StringComparer.Ordinal),
                args.Contains("--measure-frames", StringComparer.Ordinal),
                args.Contains("--inspect-traffic-line", StringComparer.Ordinal) ||
                args.Contains("--select-line", StringComparer.Ordinal) ||
                args.Contains("--select-packet", StringComparer.Ordinal),
                args.Contains("--with-origin", StringComparer.Ordinal),
                args.Contains("--refresh-metrics", StringComparer.Ordinal), Value(args, "--hover-selector"),
                args.Contains("--motion-sample", StringComparer.Ordinal),
                args.Contains("--verify-interactions", StringComparer.Ordinal), Value(args, "--language") == "en" ? "en" : "ru",
                args.Contains("--subscription-warning", StringComparer.Ordinal),
                args.Contains("--focus-panel", StringComparer.Ordinal),
                args.Contains("--verify-navigation", StringComparer.Ordinal),
                args.Contains("--verify-route-selection", StringComparer.Ordinal),
                args.Contains("--verify-zoom", StringComparer.Ordinal),
                captureMotionIndex >= 0,
                captureMotionPath);
        }

        private static int Dimension(string[] args, string key, int defaultValue, int minimum)
        {
            var value = Value(args, key);
            if (value is null)
                return defaultValue;
            if (!int.TryParse(value, out var dimension) || dimension < minimum)
                throw new ArgumentException($"{key} must be an integer of at least {minimum}.");
            return dimension;
        }

        private static string? Value(string[] args, string key)
        {
            var index = Array.IndexOf(args, key);
            return index >= 0 && index + 1 < args.Length ? args[index + 1] : null;
        }
    }
}

internal static class Program
{
    private static int _crashReportWritten;

    internal static int QaExitCode { get; set; }

    [STAThread]
    public static int Main(string[] args)
    {
        Environment.SetEnvironmentVariable("DEYTT_QA_WEBVIEW2_USER_DATA_FOLDER",
            Path.Combine(Path.GetTempPath(), $"DeyttConnect-QA-WebView2-{Environment.ProcessId}"));
        RegisterSanitizedCrashDiagnostics();
        BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);
        return QaExitCode;
    }

    private static void RegisterSanitizedCrashDiagnostics()
    {
        var path = Environment.GetEnvironmentVariable("DEYTT_QA_CRASH_REPORT");
        if (string.IsNullOrWhiteSpace(path) ||
            !string.Equals(Path.GetFileName(path), "fixture-crash.json", StringComparison.Ordinal))
            return;

        AppDomain.CurrentDomain.UnhandledException += (_, eventArgs) =>
        {
            if (eventArgs.ExceptionObject is Exception exception)
                WriteSanitizedCrashReport(path, exception);
        };
        TaskScheduler.UnobservedTaskException += (_, eventArgs) =>
            WriteSanitizedCrashReport(path, eventArgs.Exception);
    }

    private static void WriteSanitizedCrashReport(string path, Exception exception)
    {
        if (Interlocked.Exchange(ref _crashReportWritten, 1) != 0)
            return;

        try
        {
            var methods = new StackTrace(exception, false).GetFrames() ?? [];
            var report = new
            {
                schema = 1,
                exception_type = exception.GetType().Name,
                stack_methods = methods.Take(16).Select(frame =>
                {
                    var method = frame.GetMethod();
                    return string.Join(".", new[] { method?.DeclaringType?.Name, method?.Name }
                        .Where(name => !string.IsNullOrWhiteSpace(name)));
                }).ToArray(),
            };
            var temporary = path + ".tmp";
            File.WriteAllText(temporary, JsonSerializer.Serialize(report));
            File.Move(temporary, path);
        }
        catch
        {
            // Crash diagnostics are optional; never replace the original failure.
        }
    }

    private static AppBuilder BuildAvaloniaApp() => AppBuilder.Configure<QaApp>()
        .UsePlatformDetect()
        .ConfigureFonts(fontManager => fontManager.AddFontCollection(new DeyttConnect.Windows.DeyttFontCollection()))
        .LogToTrace();
}
