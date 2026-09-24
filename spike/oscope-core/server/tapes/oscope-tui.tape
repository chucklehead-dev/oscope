# Recording of the real oscope-tui in a real terminal (VHS drives ttyd).
# Run through ./record.sh, which starts a seeded oscope and sets OSCOPE_URL.
Output "../docs/oscope-tui-vhs.gif"

Require oscope-tui

Set Shell "bash"
Set FontSize 15
Set FontFamily "DejaVu Sans Mono"
Set Width 1440
Set Height 860
Set Padding 18
Set WindowBar Colorful
Set BorderRadius 10
Set Margin 0
Set TypingSpeed 55ms
Set Theme {"name":"oscope","background":"#0e1116","foreground":"#d9dfe7","selection":"#1b2d2f","cursor":"#4fb3a6","black":"#151a21","red":"#e5484d","green":"#30a46c","yellow":"#f0a032","blue":"#3e8fd6","magenta":"#d55181","cyan":"#4fb3a6","white":"#d9dfe7","brightBlack":"#5d6877","brightRed":"#ff6b70","brightGreen":"#4cc38a","brightYellow":"#ffc05c","brightBlue":"#6aaef0","brightMagenta":"#e67aa3","brightCyan":"#6fd0c3","brightWhite":"#ffffff"}

Hide
Type 'clear && oscope-tui --url "$OSCOPE_URL" --range 1h' Enter
Sleep 1.5s
Show
Sleep 2s

# search: type a query and run it
Type "/"
Sleep 300ms
Type "level:error service:payments"
Sleep 600ms
Enter
Sleep 2s

# details for the newest error
Enter
Sleep 2.2s

# jump into its trace and inspect the failing span
Type "t"
Sleep 2s
Down 6
Sleep 500ms
Enter
Sleep 2.8s
Escape
Sleep 700ms
Escape
Sleep 1s

# switch to traces, slow failed spans
Tab
Sleep 800ms
Type "/"
Type "duration:>500 status:error"
Enter
Sleep 2.2s

# services, then live mode
Type "s"
Sleep 2.8s
Type "L"
Sleep 3.5s
Type "q"
Sleep 500ms
