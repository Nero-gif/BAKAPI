package cz.nero.bakapi;

import cz.nero.bakapi.ui.BakapiFrame;
import javax.swing.SwingUtilities;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new BakapiFrame().setVisible(true));
    }
}
