using Avalonia;
using Avalonia.Controls;
using Avalonia.Animation;
using Avalonia.Animation.Easings;
using Avalonia.Media;

namespace DeyttConnect.Windows.UI;

internal static class DeyttTheme
{
    private sealed record Palette(
        Color Background, Color Header, Color Surface, Color Surface2, Color Text, Color Muted,
        Color Line, Color Accent, Color AccentHover, Color Selected, Color SelectedLine,
        Color Good, Color Warning, Color Error, Color MapSurface, Color MapLine,
        Color BlueSurface, Color SkySurface, Color MintSurface);

    private static readonly Palette Dark = new(
        Color.Parse("#080D17"), Color.Parse("#0B1220"), Color.Parse("#111A29"), Color.Parse("#162235"),
        Color.Parse("#F2F5FC"), Color.Parse("#B2BED1"), Color.Parse("#27364B"), Color.Parse("#6378F6"),
        Color.Parse("#4558D6"), Color.Parse("#192743"), Color.Parse("#354A71"), Color.Parse("#39E8BD"),
        Color.Parse("#FFC76E"), Color.Parse("#FF8295"), Color.Parse("#080D17"), Color.Parse("#27364B"),
        Color.Parse("#1B2942"), Color.Parse("#192743"), Color.Parse("#17362F"));

    private static readonly Palette Light = new(
        Color.Parse("#F6F7FB"), Colors.White, Colors.White, Color.Parse("#EEF1F8"),
        Color.Parse("#111823"), Color.Parse("#46546B"), Color.Parse("#E0E5EF"), Color.Parse("#4F63E8"),
        Color.Parse("#4558D6"), Color.Parse("#E4E8FB"), Color.Parse("#C7D0F2"), Color.Parse("#117A68"),
        Color.Parse("#986300"), Color.Parse("#B4233B"), Color.Parse("#F6F7FB"), Color.Parse("#E0E5EF"),
        Color.Parse("#E4E8FB"), Color.Parse("#E9EDFF"), Color.Parse("#E1F2ED"));

    private static Palette _palette = Dark;
    public static bool IsDark { get; private set; } = true;
    public static bool ReduceMotion { get; set; }
    public static void SetDark(bool isDark)
    {
        IsDark = isDark;
        _palette = isDark ? Dark : Light;
    }

    public static Color Background => _palette.Background;
    public static Color Header => _palette.Header;
    public static Color Surface => _palette.Surface;
    public static Color Surface2 => _palette.Surface2;
    public static Color Line => _palette.Line;
    public static Color Text => _palette.Text;
    public static Color Muted => _palette.Muted;
    public static Color Blue => _palette.Accent;
    public static Color BlueDeep => _palette.AccentHover;
    public static Color Sky => _palette.Accent;
    public static Color Mint => _palette.Good;
    public static Color Coral => _palette.Error;
    public static Color Amber => _palette.Warning;
    public static Color MapSurface => _palette.MapSurface;
    public static Color MapLine => _palette.MapLine;
    public static Color Selected => _palette.Selected;
    public static Color SelectedLine => _palette.SelectedLine;
    public static Color BlueSurface => _palette.BlueSurface;
    public static Color SkySurface => _palette.SkySurface;
    public static Color MintSurface => _palette.MintSurface;
    public static Color DialogAccent => Color.Parse("#38BDF8");
    public static Color DialogActionTop => Color.Parse("#1D8CF8");
    public static Color DialogActionBottom => Color.Parse("#1170DC");
    public static Color DialogSelected => IsDark ? Color.Parse("#2439E8BD") : Color.Parse("#24117A68");
    public static Color DialogSelectionLine => Color.FromArgb(125, Mint.R, Mint.G, Mint.B);
    public static Color DialogLine => IsDark ? Color.Parse("#2EFFFFFF") : Color.Parse("#283B5877");
    public static Brush DialogSurface => new LinearGradientBrush
    {
        StartPoint = new RelativePoint(0, 0, RelativeUnit.Relative),
        EndPoint = new RelativePoint(1, 1, RelativeUnit.Relative),
        GradientStops =
        {
            new GradientStop(Color.Parse(IsDark ? "#17FFFFFF" : "#B3FFFFFF"), 0),
            new GradientStop(Color.Parse(IsDark ? "#05FFFFFF" : "#40FFFFFF"), 1),
        },
    };
    public static Brush DialogActionFill => new LinearGradientBrush
    {
        StartPoint = new RelativePoint(0, 0, RelativeUnit.Relative),
        EndPoint = new RelativePoint(0, 1, RelativeUnit.Relative),
        GradientStops =
        {
            new GradientStop(DialogActionTop, 0),
            new GradientStop(DialogActionBottom, 1),
        },
    };

    public static FontFamily InterTight { get; } = new("fonts:DEYTT#Inter Tight");
    public static FontFamily Unbounded { get; } = new("fonts:DEYTT#Unbounded");
    public static FontFamily JetBrainsMono { get; } = new("fonts:DEYTT#JetBrains Mono");

    public static SolidColorBrush Brush(Color color) => new(color);

    public static TextBlock TextBlock(
        string value,
        double size = 16,
        Color? color = null,
        FontWeight? weight = null,
        FontFamily? family = null,
        bool wrap = true)
    {
        return CreateTextBlock(value.ToLowerInvariant(), size, color, weight, family, wrap);
    }

    public static TextBlock RawTextBlock(
        string value,
        double size = 16,
        Color? color = null,
        FontWeight? weight = null,
        FontFamily? family = null,
        bool wrap = true) => CreateTextBlock(value, size, color, weight, family, wrap);

    private static TextBlock CreateTextBlock(
        string value, double size, Color? color, FontWeight? weight, FontFamily? family, bool wrap)
    {
        return new TextBlock
        {
            Text = value,
            FontFamily = family ?? InterTight,
            FontSize = Math.Max(13, size),
            FontWeight = weight ?? FontWeight.Normal,
            Foreground = Brush(color ?? Text),
            TextWrapping = wrap ? TextWrapping.Wrap : TextWrapping.NoWrap,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
    }

    public static Border Card(
        Control child,
        Color? fill = null,
        Color? stroke = null,
        double radius = 22,
        Thickness? padding = null)
    {
        return new Border
        {
            Background = Brush(fill ?? Surface),
            BorderBrush = Brush(stroke ?? Line),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(radius),
            Padding = padding ?? new Thickness(18),
            Child = child,
        };
    }

    public static Border DialogCard(Control child, double radius = 22, Thickness? padding = null)
    {
        return new Border
        {
            Background = new LinearGradientBrush
            {
                StartPoint = new RelativePoint(0, 0, RelativeUnit.Relative),
                EndPoint = new RelativePoint(0, 1, RelativeUnit.Relative),
                GradientStops =
                {
                    new GradientStop(Color.Parse(IsDark ? "#BF344256" : "#DCFFFFFF"), 0),
                    new GradientStop(Color.Parse(IsDark ? "#C4101B2C" : "#D4EEF2F8"), 1),
                },
            },
            BorderBrush = new LinearGradientBrush
            {
                StartPoint = new RelativePoint(0, 0, RelativeUnit.Relative),
                EndPoint = new RelativePoint(0, 1, RelativeUnit.Relative),
                GradientStops =
                {
                    new GradientStop(Color.FromArgb(IsDark ? (byte)120 : (byte)180,
                        IsDark ? (byte)255 : DialogAccent.R, IsDark ? (byte)255 : DialogAccent.G,
                        IsDark ? (byte)255 : DialogAccent.B), 0),
                    new GradientStop(Color.FromArgb(70, Line.R, Line.G, Line.B), 1),
                },
            },
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(radius),
            Padding = padding ?? new Thickness(18),
            BoxShadow = new BoxShadows(new BoxShadow
            {
                Blur = 34,
                Spread = 1,
                OffsetY = 12,
                Color = Color.Parse(IsDark ? "#66000000" : "#260C3153"),
            }),
            Child = child,
        };
    }

    public static Border DialogPrimaryButton(Control label, Action onClick)
    {
        label.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        if (label is TextBlock text) text.TextAlignment = TextAlignment.Center;
        var button = Action(label, onClick);
        button.HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        button.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch;
        button.MinHeight = 46;
        button.BorderBrush = Brush(Colors.Transparent);
        button.BorderThickness = new Thickness(0);
        button.CornerRadius = new CornerRadius(14);
        var surface = new Border
        {
            MinHeight = 48,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
            CornerRadius = new CornerRadius(14),
            Child = button,
        };
        StyleDialogPrimarySurface(surface);
        button.PointerEntered += (_, _) => surface.Opacity = 0.94;
        button.PointerExited += (_, _) => surface.Opacity = 1;
        return surface;
    }

    public static void StyleDialogPrimarySurface(Border surface)
    {
        surface.Background = DialogActionFill;
        surface.BorderBrush = Brush(Color.FromArgb(90, 255, 255, 255));
        surface.BorderThickness = new Thickness(1);
        surface.BoxShadow = new BoxShadows(new BoxShadow
        {
            Blur = 24,
            OffsetY = 6,
            Color = Color.Parse("#661D8CF8"),
        });
    }

    public static Border DialogPanel(Control child, double radius = 16, Thickness? padding = null) => new()
    {
        Child = child,
        Background = DialogSurface,
        BorderBrush = Brush(DialogLine),
        BorderThickness = new Thickness(1),
        CornerRadius = new CornerRadius(radius),
        Padding = padding ?? new Thickness(16),
    };

    public static void StyleDialogButton(Button button, bool primary = false)
    {
        button.Classes.Add("deytt-button");
        button.FontFamily = InterTight;
        button.FontSize = 14;
        button.FontWeight = FontWeight.SemiBold;
        button.MinHeight = 40;
        button.Padding = new Thickness(14, 8);
        button.CornerRadius = new CornerRadius(12);
        button.HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        button.Background = primary ? DialogActionFill : DialogSurface;
        button.Foreground = Brush(primary ? Colors.White : Text);
        button.BorderBrush = Brush(DialogLine);
        button.BorderThickness = new Thickness(1);
    }

    public static void StyleDialogInput(TextBox input)
    {
        input.Classes.Add("glass-input");
        input.FontFamily = InterTight;
        input.FontSize = 14;
        input.MinHeight = 42;
        input.Padding = new Thickness(14, 10);
        input.CornerRadius = new CornerRadius(12);
        input.Background = DialogSurface;
        input.Foreground = Brush(Text);
        input.BorderBrush = Brush(DialogLine);
        input.BorderThickness = new Thickness(1);
        input.SelectionBrush = Brush(DialogSelectionLine);
        input.CaretBrush = Brush(DialogAccent);
    }

    public static Button DialogChoice(Control labels, bool selected, Action onClick)
    {
        var row = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto"), ColumnSpacing = 16 };
        row.Children.Add(labels);
        var mark = TextBlock(selected ? "✓" : "○", 18, selected ? Mint : Muted, FontWeight.SemiBold, wrap: false);
        Grid.SetColumn(mark, 1);
        row.Children.Add(mark);
        var surface = DialogPanel(row, padding: new Thickness(18, 16));
        if (selected)
        {
            surface.Background = Brush(DialogSelected);
            surface.BorderBrush = Brush(DialogSelectionLine);
        }
        var button = Action(surface, onClick);
        button.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch;
        button.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Stretch;
        button.VerticalContentAlignment = Avalonia.Layout.VerticalAlignment.Stretch;
        return button;
    }

    public static Button Action(Control child, Action onClick)
    {
        var button = new Button
        {
            Content = child,
            Background = Brush(Colors.Transparent),
            BorderBrush = Brush(Colors.Transparent),
            BorderThickness = new Thickness(0),
            CornerRadius = new CornerRadius(12),
            Padding = new Thickness(0),
            HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
            VerticalContentAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        button.Classes.Add("deytt-button");
        var scale = new ScaleTransform(1, 1)
        {
            Transitions = new Transitions
            {
                new DoubleTransition { Property = ScaleTransform.ScaleXProperty, Duration = TimeSpan.FromMilliseconds(140), Easing = new CubicEaseOut() },
                new DoubleTransition { Property = ScaleTransform.ScaleYProperty, Duration = TimeSpan.FromMilliseconds(140), Easing = new CubicEaseOut() },
            },
        };
        button.RenderTransform = scale;
        void SetScale(double value) => scale.ScaleX = scale.ScaleY = ReduceMotion ? 1 : value;
        button.PointerEntered += (_, _) => SetScale(1.012);
        button.PointerExited += (_, _) => SetScale(1);
        button.PointerPressed += (_, _) => SetScale(.975);
        button.PointerReleased += (_, _) => SetScale(button.IsPointerOver ? 1.012 : 1);
        button.PointerCaptureLost += (_, _) => SetScale(button.IsPointerOver ? 1.012 : 1);
        button.Click += (_, _) => onClick();
        return button;
    }

    public static Border PrimaryButton(string label, Action onClick)
    {
        var labelText = TextBlock(label, 18, Colors.White, FontWeight.SemiBold, wrap: false);
        labelText.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch;
        labelText.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
        labelText.TextAlignment = TextAlignment.Center;
        var button = Action(
            labelText,
            onClick);
        button.HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Stretch;
        button.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch;
        return new Border
        {
            MinHeight = 48,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
            Background = Brush(BlueDeep),
            CornerRadius = new CornerRadius(12),
            Child = button,
        };
    }

    public static Border IconTile(string glyph, double size = 56, Color? color = null)
    {
        var icon = TextBlock(glyph, size <= 44 ? 18 : 22,
            color ?? Sky, FontWeight.SemiBold, JetBrainsMono, wrap: false);
        icon.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        icon.TextAlignment = TextAlignment.Center;
        return new Border
        {
            Width = size,
            Height = size,
            Background = Brush(Surface2),
            BorderBrush = Brush(Line),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(17),
            Child = icon,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
    }

    public static Border Spacer(double height) => new() { Height = height };

    public static TextBlock SectionLabel(string label) => TextBlock(
        label.ToLowerInvariant(), 11, Muted, FontWeight.SemiBold, JetBrainsMono, wrap: false);

    public static Border Hairline(double left = 0) => new()
    {
        Height = 1,
        Margin = new Thickness(left, 0, 0, 0),
        Background = Brush(Line),
    };
}
