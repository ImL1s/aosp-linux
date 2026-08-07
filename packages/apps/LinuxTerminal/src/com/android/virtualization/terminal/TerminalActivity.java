package com.android.virtualization.terminal;

import android.app.Activity;
import android.os.Bundle;
import android.system.linux.LinuxManager;
import android.widget.LinearLayout;
import android.widget.Button;

import com.android.virtualization.terminal.ime.*;
import com.android.virtualization.terminal.net.*;
import com.android.virtualization.terminal.parser.*;
import com.android.virtualization.terminal.renderer.*;
import com.android.virtualization.terminal.touch.*;
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

        // Shortcut Action Bar for Terminal (Ctrl, Alt, Esc, Tab, Touch Mode Toggle)
        LinearLayout actionBar = new LinearLayout(this);
        actionBar.setOrientation(LinearLayout.HORIZONTAL);

        Button btnMode = new Button(this);
        btnMode.setText("Switch Mode");
        btnMode.setOnClickListener(v -> {
            TouchModeStateMachine.TouchMode currentMode = mTerminalView.getTouchMode();
            TouchModeStateMachine.TouchMode nextMode = TouchModeStateMachine.TouchMode.values()[(currentMode.ordinal() + 1) % 3];
            mTerminalView.setTouchMode(nextMode);
            btnMode.setText("Mode: " + nextMode.name());
        });

        actionBar.addView(btnMode);
        mainLayout.addView(actionBar);

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
}
