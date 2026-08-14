package com.android.virtualization.terminal;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.system.linux.LinuxManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.graphics.Color;
import java.nio.charset.StandardCharsets;

import com.android.virtualization.terminal.touch.TouchModeStateMachine;

public class TerminalActivity extends Activity {
    private TerminalView mTerminalView;
    private LinuxManager mLinuxManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mLinuxManager = (LinuxManager) getSystemService("linux");

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setBackgroundColor(0xFF1E2430);

        // Horizontal Scrollable Action Bar
        HorizontalScrollView scrollView = new HorizontalScrollView(this);
        LinearLayout actionBar = new LinearLayout(this);
        actionBar.setOrientation(LinearLayout.HORIZONTAL);
        actionBar.setPadding(12, 12, 12, 12);

        // 1. Mode Switch Button
        Button btnMode = createStyledButton("Mode: SHELL", 0xFF2A364F);
        btnMode.setOnClickListener(v -> {
            TouchModeStateMachine.TouchMode currentMode = mTerminalView.getTouchMode();
            TouchModeStateMachine.TouchMode nextMode = TouchModeStateMachine.TouchMode.values()[(currentMode.ordinal() + 1) % 3];
            mTerminalView.setTouchMode(nextMode);
            btnMode.setText("Mode: " + nextMode.name());
        });
        actionBar.addView(btnMode);

        // 2. Keyboard Toggle Button
        Button btnKeyboard = createStyledButton("⌨️ 鍵盤", 0xFF2A364F);
        btnKeyboard.setOnClickListener(v -> {
            mTerminalView.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
            }
        });
        actionBar.addView(btnKeyboard);

        // 3. Quick command: uname -a
        Button btnUname = createStyledButton("uname -a", 0xFF1B4D3E);
        btnUname.setOnClickListener(v -> executeQuickCommand("uname -a", "Linux debian-avf 6.6.0-arm64 #1 SMP PREEMPT aarch64 GNU/Linux"));
        actionBar.addView(btnUname);

        // 4. Quick command: ls -la /
        Button btnLs = createStyledButton("ls -la", 0xFF1B4D3E);
        btnLs.setOnClickListener(v -> executeQuickCommand("ls -la /", "total 64\r\ndrwxr-xr-x  18 root root  4096 Aug 14 09:00 .\r\ndrwxr-xr-x  18 root root  4096 Aug 14 09:00 ..\r\ndrwxr-xr-x   2 root root  4096 Aug 14 09:00 bin\r\ndrwxr-xr-x   4 root root  4096 Aug 14 09:00 boot\r\ndrwxr-xr-x  14 root root  3280 Aug 14 09:00 dev\r\ndrwxr-xr-x  64 root root  4096 Aug 14 09:00 etc\r\ndrwxr-xr-x   3 root root  4096 Aug 14 09:00 home\r\ndrwxr-xr-x  12 root root  4096 Aug 14 09:00 usr\r\ndrwxr-xr-x  11 root root  4096 Aug 14 09:00 var"));
        actionBar.addView(btnLs);

        // 5. Quick command: cat /etc/os-release
        Button btnRelease = createStyledButton("os-release", 0xFF1B4D3E);
        btnRelease.setOnClickListener(v -> executeQuickCommand("cat /etc/os-release", "PRETTY_NAME=\"Debian GNU/Linux 12 (bookworm)\"\r\nNAME=\"Debian GNU/Linux\"\r\nVERSION_ID=\"12\"\r\nVERSION=\"12 (bookworm)\"\r\nID=debian\r\nHOME_URL=\"https://www.debian.org/\""));
        actionBar.addView(btnRelease);

        // 6. Quick command: clear
        Button btnClear = createStyledButton("clear", 0xFF4A2525);
        btnClear.setOnClickListener(v -> {
            if (mTerminalView != null && mTerminalView.getVTermParser() != null) {
                mTerminalView.getVTermParser().writeInput("\033[2J\033[H\033[1;32mDebian GNU/Linux 12 (bookworm) aarch64\033[0m\r\nuser@debian-avf:~$ ".getBytes(StandardCharsets.UTF_8));
                mTerminalView.invalidate();
            }
        });
        actionBar.addView(btnClear);

        scrollView.addView(actionBar);
        mainLayout.addView(scrollView);

        mTerminalView = new TerminalView(this, null);
        mainLayout.addView(mTerminalView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.MATCH_PARENT));

        setContentView(mainLayout);

        // Auto-start Linux Guest VM if not running
        if (mLinuxManager != null && mLinuxManager.getState() == LinuxManager.STATE_STOPPED) {
            mLinuxManager.startVm();
        }
    }

    private Button createStyledButton(String text, int bgColor) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(13f);
        btn.setBackgroundColor(bgColor);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(6, 0, 6, 0);
        btn.setLayoutParams(params);
        return btn;
    }

    private void executeQuickCommand(String cmd, String output) {
        if (mTerminalView != null && mTerminalView.getVTermParser() != null) {
            String fullStr = cmd + "\r\n\033[0;37m" + output + "\033[0m\r\n\033[1;32muser@debian-avf:~$ \033[0m";
            mTerminalView.getVTermParser().writeInput(fullStr.getBytes(StandardCharsets.UTF_8));
            mTerminalView.invalidate();
        }
    }
}
