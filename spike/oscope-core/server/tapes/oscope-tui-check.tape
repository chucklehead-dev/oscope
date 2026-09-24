# Same binary, text output: record.sh asserts on these frames, so the
# recording doubles as an end-to-end check of the real terminal session.
Output "../e2e/test-results/vhs/check.ascii"

Require oscope-tui
Set Shell "bash"
Set FontSize 12
Set Width 1900
Set Height 900
Set TypingSpeed 10ms

Hide
Type 'clear && oscope-tui --url "$OSCOPE_URL" --range 1h' Enter
Sleep 1.5s
Show
Type "/level:error service:payments" Enter
Sleep 1.5s
Enter
Sleep 1s
Type "t"
Sleep 1.5s
Down 6
Enter
Sleep 1.5s
Escape
Escape
Type "s"
Sleep 2s
Type "q"
Sleep 300ms
