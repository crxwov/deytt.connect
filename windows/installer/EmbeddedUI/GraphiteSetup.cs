using System;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;
using WixToolset.Dtf.WindowsInstaller;

namespace DeyttConnect.Setup;

public sealed class GraphiteSetup : IEmbeddedUI
{
    private enum Operation { Install, Repair, Remove }

    private readonly ManualResetEvent _decision = new(false);
    private Thread _thread;
    private Wizard _wizard;
    private Exception _startupFailure;
    private Operation? _operation;
    private string _productCode;
    private bool _reboot;
    private int? _result;
    private string _error;
    private long _totalTicks;
    private long _progressTicks;
    private int _actionTicks;
    private int _direction = 1;
    private volatile bool _cancel;
    private volatile bool _allowCancel = true;

    public bool Initialize(Session session, string resourcePath, ref InstallUIOptions internalUILevel)
    {
        if (((int)internalUILevel & 7) != (int)InstallUIOptions.Full || session["ACTION"] == "ADMIN")
            return false;

        _productCode = session["ProductCode"];
        var version = session["ProductVersion"];
        var installed = InstalledProduct(_productCode);
        var previous = SetupActions.FindInstalledConnectVersion(session, includeAllRelatedProducts: true);
        var removing = string.Equals(session["REMOVE"], "ALL", StringComparison.OrdinalIgnoreCase);
        var repairing = !string.IsNullOrEmpty(session["REINSTALL"]);
        var mode = SetupActions.ClassifySetupMode(installed, previous != null, previous,
            Version.TryParse(version, out var parsed) ? parsed : null);

        _thread = new Thread(() =>
        {
            try
            {
                using var wizard = new Wizard(mode, version, previous?.ToString(3), removing, repairing,
                    operation => { _operation = operation; _decision.Set(); },
                    () => _cancel = true,
                    FinishOptionalActions);
                _wizard = wizard;
                Application.Run(wizard);
            }
            catch (Exception error)
            {
                _startupFailure = error;
            }
            finally
            {
                _decision.Set();
            }
        }) { IsBackground = true, Name = "Graphite setup UI" };
        _thread.SetApartmentState(ApartmentState.STA);
        _thread.Start();
        _decision.WaitOne();
        if (_startupFailure is not null)
        {
            session.Log("Graphite setup failed to start: {0}", _startupFailure.GetType().Name);
            return false;
        }
        if (!_operation.HasValue)
            throw new InstallCanceledException();
        if (_operation == Operation.Remove)
            session["REMOVE"] = "ALL";
        else if (_operation == Operation.Repair)
        {
            session["REINSTALL"] = "ALL";
            session["REINSTALLMODE"] = "amus";
        }

        session["GRAPHITE_SETUP_CONFIRMED"] = "1";
        internalUILevel = InstallUIOptions.NoChange | InstallUIOptions.SourceResolutionOnly;
        return true;
    }

    public MessageResult ProcessMessage(InstallMessage type, Record record, MessageButtons buttons,
        MessageIcon icon, MessageDefaultButton defaultButton)
    {
        switch (type)
        {
            case InstallMessage.ActionStart when record is not null && record.FieldCount >= 3:
                _actionTicks = 0;
                var action = record.GetString(3);
                if (!string.IsNullOrWhiteSpace(action))
                    Report(wizard => wizard.ProgressText = action);
                break;
            case InstallMessage.Progress when record is not null && record.FieldCount >= 2:
                var amount = Math.Max(0, record.GetInteger(2));
                switch (record.GetInteger(1))
                {
                    case 0:
                        _totalTicks = amount;
                        _direction = record.FieldCount >= 3 && record.GetInteger(3) == 1 ? -1 : 1;
                        _progressTicks = _direction == 1 ? 0 : _totalTicks;
                        _actionTicks = 0;
                        break;
                    case 1:
                        _actionTicks = record.FieldCount >= 3 && record.GetInteger(3) == 1 ? amount : 0;
                        break;
                    case 2:
                        _progressTicks += _direction * (long)amount;
                        break;
                    case 3:
                        _totalTicks += amount;
                        break;
                }
                ReportProgress();
                break;
            case InstallMessage.ActionData when _actionTicks > 0:
                _progressTicks += _direction * (long)_actionTicks;
                ReportProgress();
                break;
            case InstallMessage.CommonData when record is not null && record.FieldCount >= 2 && record.GetInteger(1) == 2:
                _allowCancel = record.GetInteger(2) == 1;
                Report(wizard => wizard.CanCancel = _allowCancel);
                break;
            case InstallMessage.InstallEnd when record is not null && record.FieldCount >= 3 &&
                string.Equals(record.GetString(2)?.Trim(), _productCode, StringComparison.OrdinalIgnoreCase):
                var result = record.GetInteger(3);
                // MSI 4.5+ INSTALLMESSAGE_INSTALLEND field 3 uses Win32 dialog IDs (1=IDOK, 2=IDCANCEL, 3=IDABORT).
                _result = result switch
                {
                    1 or 0 or 3010 or 1641 => 0,
                    2 or 1602 => 1602,
                    _ => result
                };
                if (result is 3010 or 1641) _reboot = true;
                break;
            case InstallMessage.FatalExit:
            case InstallMessage.Error:
            case InstallMessage.OutOfDiskSpace:
                var msg = record?.ToString();
                if (!string.IsNullOrWhiteSpace(msg)) _error = msg;
                if (buttons == MessageButtons.OK)
                    return MessageResult.OK;
                break;
        }
        return _cancel && _allowCancel && (type == InstallMessage.Progress ||
            type == InstallMessage.ActionData || type == InstallMessage.ActionStart)
            ? MessageResult.Cancel : MessageResult.None;
    }

    public void Shutdown()
    {
        var installed = InstalledProduct(_productCode);
        var success = _result.HasValue
            ? _result is 0 or 3010 or 1641
            : !_cancel && _error is null && (_operation == Operation.Remove ? !installed : installed);
        Report(wizard => wizard.Finish(success, !success && (_result == 1602 || _cancel), _reboot, _error));
        _thread?.Join();
        _decision.Dispose();
    }

    private static bool InstalledProduct(string code)
    {
        try { return new ProductInstallation(code).IsInstalled; }
        catch (InstallerException) { return false; }
    }

    private void ReportProgress()
    {
        if (_totalTicks <= 0) return;
        var percent = (int)Math.Max(0, Math.Min(100, _progressTicks * 100 / _totalTicks));
        Report(wizard => wizard.SetProgress(percent));
    }

    private void Report(Action<Wizard> change)
    {
        var wizard = _wizard;
        if (wizard is null || wizard.IsDisposed || !wizard.IsHandleCreated) return;
        try { wizard.BeginInvoke((Action)(() => change(wizard))); }
        catch (InvalidOperationException) { }
    }

    private string FinishOptionalActions(bool shortcut, bool launch)
    {
        if (!shortcut && (!launch || _reboot)) return null;
        try
        {
            var path = new ProductInstallation(_productCode).InstallLocation;
            if (string.IsNullOrEmpty(path))
                return "путь установки не определён. откройте ./connect из меню «пуск».";
            return SetupActions.FinishInstalledConnect(path, shortcut, launch && !_reboot);
        }
        catch (Exception)
        {
            return "не удалось выполнить дополнительное действие. откройте ./connect из меню «пуск».";
        }
    }

    private sealed class Wizard : Form
    {
        private static readonly Color BackgroundInk = Color.FromArgb(19, 19, 19);
        private static readonly Color RaisedInk = Color.FromArgb(27, 27, 27);
        private static readonly Color LineInk = Color.FromArgb(55, 55, 55);
        private static readonly Color TextInk = Color.FromArgb(245, 245, 245);
        private static readonly Color MutedInk = Color.FromArgb(174, 174, 174);
        private static readonly Color BrandInk = Color.FromArgb(122, 211, 236);
        private readonly Control _step;
        private readonly Control _heading;
        private readonly Control _copy;
        private readonly Control _progressText;
        private readonly Panel _track;
        private readonly Panel _fill;
        private readonly Button _secondary;
        private readonly Button _primary;
        private readonly Button _close;
        private readonly CheckBox _shortcut;
        private readonly CheckBox _launch;
        private readonly Action<Operation> _start;
        private readonly Action _cancel;
        private readonly Func<bool, bool, string> _finish;
        private readonly string _mode;
        private readonly string _version;
        private readonly string _previous;
        private bool _running;
        private bool _complete;
        private bool _success;
        private Operation _selected;

        public Wizard(string mode, string version, string previous, bool removing, bool repairing,
            Action<Operation> start, Action cancel, Func<bool, bool, string> finish)
        {
            _mode = mode;
            _version = version;
            _previous = previous;
            _start = start;
            _cancel = cancel;
            _finish = finish;
            BackColor = BackgroundInk;
            ForeColor = TextInk;
            Font = new Font("Segoe UI", 10);
            Text = "./connect — установка";
            using (var iconStream = typeof(Wizard).Assembly.GetManifestResourceStream("deyttconnect.ico"))
                if (iconStream is not null) Icon = new Icon(iconStream);
            AccessibleName = Text;
            StartPosition = FormStartPosition.CenterScreen;
            FormBorderStyle = FormBorderStyle.None;
            AutoScaleMode = AutoScaleMode.Dpi;
            AutoScaleDimensions = new SizeF(96, 96);
            ClientSize = new Size(624, 425);
            Padding = new Padding(1);
            KeyPreview = true;
            Paint += (_, e) =>
            {
                using var stroke = new Pen(LineInk);
                e.Graphics.DrawRectangle(stroke, 0, 0, Width - 1, Height - 1);
            };

            var header = new Panel { Bounds = new Rectangle(1, 1, 622, 64), BackColor = RaisedInk };
            Controls.Add(header);
            header.Paint += (_, e) =>
            {
                e.Graphics.TextRenderingHint = System.Drawing.Text.TextRenderingHint.AntiAliasGridFit;
                using var logoFont = new Font("Segoe UI", 18, FontStyle.Bold);
                using var brandBrush = new SolidBrush(BrandInk);
                using var textBrush = new SolidBrush(TextInk);
                var fmt = StringFormat.GenericTypographic;
                e.Graphics.DrawString("./", logoFont, brandBrush, 26, 15, fmt);
                var markWidth = e.Graphics.MeasureString("./", logoFont, PointF.Empty, fmt).Width;
                e.Graphics.DrawString("connect", logoFont, textBrush, 26 + markWidth, 15, fmt);
            };
            _close = Button("×", 568, 14, 38, 36, false, 8);
            _close.AccessibleName = "закрыть установщик";
            _close.Click += (_, _) => Close();
            header.Controls.Add(_close);
            header.Controls.Add(Label("windows · x64", 9, MutedInk, 445, 22, 105, 22));
            foreach (Control control in header.Controls)
                if (control is SmoothLabel) control.MouseDown += Drag;
            header.MouseDown += Drag;
            Controls.Add(new Panel { Bounds = new Rectangle(1, 65, 622, 1), BackColor = LineInk });

            _step = Label("", 10, MutedInk, 30, 94, 320, 22);
            _heading = Label("", 23, TextInk, 30, 123, 564, 49, true);
            _copy = Label("", 11, MutedInk, 30, 182, 564, 85);
            _progressText = Label("", 10, MutedInk, 30, 294, 564, 36);
            _track = new Panel { Bounds = new Rectangle(30, 277, 564, 6), BackColor = LineInk, Visible = false };
            _fill = new Panel { Bounds = new Rectangle(0, 0, 0, 6), BackColor = TextInk, Dock = DockStyle.Left };
            _track.Controls.Add(_fill);
            _shortcut = Checkbox("ярлык на рабочем столе", 266);
            _launch = Checkbox("открыть ./connect после установки", 304);
            _secondary = Button("отмена", 294, 365, 136, 40, false);
            _secondary.Click += (_, _) => BackOrCancel();
            _primary = Button("продолжить", 446, 365, 148, 40, true);
            _primary.Click += (_, _) => Primary();
            Controls.AddRange(new Control[] { _step, _heading, _copy, _progressText, _track,
                _shortcut, _launch, _secondary, _primary,
                new Panel { Bounds = new Rectangle(1, 344, 622, 1), BackColor = LineInk } });
            AcceptButton = _primary;
            if (removing || repairing)
                Confirm(removing ? Operation.Remove : Operation.Repair);
            else
                Welcome();
        }

        private bool _confirming;
        public string ProgressText { set => _progressText.Text = value; }
        public bool CanCancel
        {
            set
            {
                _secondary.Enabled = value;
                _close.Enabled = value;
                if (_running)
                {
                    _secondary.Visible = value;
                    _close.Visible = value;
                }
            }
        }
        public void SetProgress(int percentage) => _fill.Width = _track.Width * percentage / 100;

        private void Welcome()
        {
            _confirming = false;
            _step.Text = _mode == "update" ? $"обновление до {_version}" : $"версия {_version}";
            _heading.Text = _mode == "update" ? "обновление" : _mode == "maintenance" ? "управление приложением" : "установка";
            _copy.Text = _mode == "update"
                ? $"версия {_previous} → {_version}\r\nвход и настройки сохранятся."
                : _mode == "maintenance"
                    ? "восстановите файлы приложения или удалите ./connect."
                    : "установите ./connect, чтобы выбрать маршрут и подключиться.";
            _primary.Text = _mode == "maintenance" ? "восстановить" : "продолжить";
            _secondary.Bounds = new Rectangle(294, 365, 136, 40);
            _secondary.Text = _mode == "maintenance" ? "удалить" : "отмена";
        }

        private void Confirm(Operation operation)
        {
            _selected = operation;
            _confirming = true;
            _step.Text = $"версия {_version}";
            _heading.Text = operation == Operation.Remove ? "удалить приложение?"
                : operation == Operation.Repair ? "восстановить файлы?"
                : _mode == "update" ? "подтвердите обновление" : "всё готово к установке";
            _copy.Text = operation == Operation.Remove
                ? "vpn отключится. приложение и локальные данные будут удалены. подписка останется в аккаунте."
                : "windows может запросить права администратора.";
            _primary.Text = operation == Operation.Remove ? "удалить" : operation == Operation.Repair
                ? "восстановить" : _mode == "update" ? "обновить" : "установить";
            _secondary.Bounds = new Rectangle(294, 365, 136, 40);
            _secondary.Text = "назад";
        }

        private void Primary()
        {
            if (_complete)
            {
                if (_success && _selected != Operation.Remove)
                {
                    var issue = _finish(_shortcut.Checked, _launch.Checked);
                    if (issue is not null)
                    {
                        _copy.Text = issue;
                        _shortcut.Checked = _launch.Checked = false;
                        return;
                    }
                }
                Close();
                return;
            }
            if (!_confirming)
            {
                Confirm(_mode == "maintenance" ? Operation.Repair : Operation.Install);
                return;
            }

            _running = true;
            _step.Text = _selected == Operation.Remove ? "удаление" : $"версия {_version}";
            _heading.Text = _selected == Operation.Remove ? "удаляем ./connect" : "применяем изменения";
            _copy.Text = "подождите, пока windows завершит работу с файлами и службой vpn.";
            _track.Visible = true;
            _primary.Visible = false;
            _secondary.Bounds = new Rectangle(446, 365, 148, 40);
            _secondary.Text = "отмена";
            _start(_selected);
        }

        private void BackOrCancel()
        {
            if (_running || _complete) { Close(); return; }
            if (_confirming) { Welcome(); return; }
            if (_mode == "maintenance") { Confirm(Operation.Remove); return; }
            Close();
        }

        public void Finish(bool success, bool cancelled, bool reboot, string error)
        {
            _running = false;
            _complete = true;
            _success = success;
            _track.Visible = false;
            _progressText.Text = "";
            _step.Text = $"версия {_version}";
            _heading.Text = cancelled ? "операция отменена" : !success ? "операция не завершилась" :
                _selected == Operation.Remove ? "приложение удалено" : "всё готово";
            _copy.Text = cancelled ? "windows завершила отмену операции." : !success
                ? error ?? "windows installer сообщил об ошибке."
                : reboot ? "для завершения требуется перезагрузка windows."
                : _selected == Operation.Remove ? "подписка сохранена в аккаунте." : "./connect доступен в меню «пуск».";
            _shortcut.Visible = success && _selected != Operation.Remove;
            _launch.Visible = _shortcut.Visible && !reboot;
            if (reboot) _launch.Checked = false;
            _primary.Text = "готово";
            _primary.Visible = true;
            _primary.Enabled = true;
            _secondary.Visible = false;
            _secondary.Bounds = new Rectangle(294, 365, 136, 40);
            _close.Visible = true;
            _close.Enabled = true;
            _primary.Focus();
        }

        protected override bool ProcessCmdKey(ref Message message, Keys key)
        {
            if (key != Keys.Escape) return base.ProcessCmdKey(ref message, key);
            Close();
            return true;
        }

        protected override void OnFormClosing(FormClosingEventArgs args)
        {
            if (_running)
            {
                args.Cancel = true;
                if (_close.Enabled)
                {
                    _cancel();
                    _progressText.Text = "отменяем операцию…";
                    CanCancel = false;
                }
            }
            base.OnFormClosing(args);
        }

        private void Drag(object sender, MouseEventArgs args)
        {
            if (args.Button != MouseButtons.Left) return;
            ReleaseCapture();
            SendMessage(Handle, 0xA1, (IntPtr)2, IntPtr.Zero);
        }

        [DllImport("user32.dll")] private static extern bool ReleaseCapture();
        [DllImport("user32.dll")] private static extern IntPtr SendMessage(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam);

        private static Control Label(string text, int size, Color color, int x, int y, int w, int h, bool bold = false) =>
            new SmoothLabel(text, size, color, x, y, w, h, bold);

        private sealed class SmoothLabel : Control
        {
            private string _text;

            public SmoothLabel(string text, int size, Color color, int x, int y, int w, int h, bool bold)
            {
                _text = text;
                ForeColor = color;
                Font = new Font("Segoe UI", size, bold ? FontStyle.Bold : FontStyle.Regular);
                Bounds = new Rectangle(x, y, w, h);
                BackColor = BackgroundInk;
                SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint | ControlStyles.OptimizedDoubleBuffer, true);
            }

            public override string Text
            {
                get => _text;
                set { _text = value ?? ""; Invalidate(); }
            }

            protected override void OnPaintBackground(PaintEventArgs pevent) { }

            protected override void OnPaint(PaintEventArgs e)
            {
                e.Graphics.Clear(Parent?.BackColor ?? BackColor);
                if (string.IsNullOrEmpty(_text)) return;
                e.Graphics.TextRenderingHint = System.Drawing.Text.TextRenderingHint.AntiAliasGridFit;
                using var brush = new SolidBrush(ForeColor);
                using var fmt = new StringFormat(StringFormatFlags.LineLimit) { Trimming = StringTrimming.EllipsisWord };
                e.Graphics.DrawString(_text, Font, brush, new RectangleF(0, 0, Width, Height), fmt);
            }
        }

        private static Button Button(string text, int x, int y, int w, int h, bool primary, int radius = 10) =>
            new RoundedButton(text, x, y, w, h, primary, radius);

        private sealed class RoundedButton : Button
        {
            private readonly bool _primary;
            private readonly int _radius;
            private bool _hovered;
            private bool _pressed;

            public RoundedButton(string text, int x, int y, int w, int h, bool primary, int radius)
            {
                _primary = primary;
                _radius = radius;
                Text = text;
                Bounds = new Rectangle(x, y, w, h);
                FlatStyle = FlatStyle.Flat;
                FlatAppearance.BorderSize = 0;
                BackColor = primary ? TextInk : RaisedInk;
                ForeColor = primary ? BackgroundInk : TextInk;
                Cursor = Cursors.Hand;
                SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint | ControlStyles.OptimizedDoubleBuffer, true);
            }

            protected override bool ShowFocusCues => false;
            protected override void OnPaintBackground(PaintEventArgs pevent) { }
            protected override void OnMouseEnter(EventArgs e) { _hovered = true; Invalidate(); base.OnMouseEnter(e); }
            protected override void OnMouseLeave(EventArgs e) { _hovered = false; _pressed = false; Invalidate(); base.OnMouseLeave(e); }
            protected override void OnMouseDown(MouseEventArgs e) { _pressed = true; Invalidate(); base.OnMouseDown(e); }
            protected override void OnMouseUp(MouseEventArgs e) { _pressed = false; Invalidate(); base.OnMouseUp(e); }

            protected override void OnPaint(PaintEventArgs e)
            {
                e.Graphics.Clear(Parent?.BackColor ?? BackgroundInk);
                e.Graphics.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
                e.Graphics.PixelOffsetMode = System.Drawing.Drawing2D.PixelOffsetMode.HighQuality;
                e.Graphics.TextRenderingHint = System.Drawing.Text.TextRenderingHint.AntiAliasGridFit;
                var fill = _primary
                    ? (_pressed ? Color.FromArgb(197, 197, 197) : _hovered ? Color.White : TextInk)
                    : (_pressed ? LineInk : _hovered ? Color.FromArgb(41, 41, 41) : RaisedInk);
                var border = _primary ? fill : LineInk;
                using var path = RoundedRect(new RectangleF(0.5f, 0.5f, Width - 2f, Height - 2f), _radius);
                using var brush = new SolidBrush(fill);
                using var pen = new Pen(border, 1f);
                e.Graphics.FillPath(brush, path);
                e.Graphics.DrawPath(pen, path);
                using var textBrush = new SolidBrush(ForeColor);
                using var fmt = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
                e.Graphics.DrawString(Text, Font, textBrush, new RectangleF(0, 0, Width, Height), fmt);
            }

            private static System.Drawing.Drawing2D.GraphicsPath RoundedRect(RectangleF bounds, float radius)
            {
                float d = radius * 2f;
                var path = new System.Drawing.Drawing2D.GraphicsPath();
                path.AddArc(bounds.X, bounds.Y, d, d, 180, 90);
                path.AddArc(bounds.Right - d, bounds.Y, d, d, 270, 90);
                path.AddArc(bounds.Right - d, bounds.Bottom - d, d, d, 0, 90);
                path.AddArc(bounds.X, bounds.Bottom - d, d, d, 90, 90);
                path.CloseFigure();
                return path;
            }
        }

        private static CheckBox Checkbox(string text, int y) =>
            new() { Text = text, Bounds = new Rectangle(30, y, 520, 30), Checked = true,
                ForeColor = TextInk, FlatStyle = FlatStyle.Flat, BackColor = BackgroundInk, Visible = false };
    }
}
