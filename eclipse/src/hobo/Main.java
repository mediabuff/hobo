package hobo;

import hobo.graphics.GamePanel;

import java.util.*;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import javax.swing.*;

public class Main {
	public static void main(String[] args) {
		graphical();
	}
	
	public static void textual() {
		PlayerInteraction ui = new TextualPlayerInteraction();
		Game g = new Game(new HumanPlayer("x", ui));
		g.registerObserver(ui);
		g.play();
	}

	public static void graphical() {
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				final JFrame f = new JFrame("crapshoot");
				
				JButton b = new JButton("new game...");
				b.addActionListener(new ActionListener() {
					private Thread gameThread = null;
					private GamePanel gameVisualization = null; // hate hate hate

					public void actionPerformed(ActionEvent e) {
						final GamePanel gv = new GamePanel();
						final Game g = new Game(new HumanPlayer("woop", gv.getUserInterface()),
						                        new HumanPlayer("dee",  gv.getUserInterface()),
						                        new HumanPlayer("doo",  gv.getUserInterface()));

						g.registerObserver(new GameObserver() {
							@Override public void observe(Event e) {
								gv.reflect(e.state);
								gv.repaint();
							}
						});
						
						if (gameVisualization != null)
							f.remove(gameVisualization);
						gameVisualization = gv;
						f.add(gameVisualization, BorderLayout.CENTER);
						
						f.validate();

						if (gameThread != null)
							gameThread.interrupt();
						gameThread = new Thread(new Runnable() {
							public void run() { g.play(); }
						});
						gameThread.start();
					}
				});
				
				f.add(b, BorderLayout.PAGE_END);
				
				b.doClick();
				
				f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
				f.pack();
				f.setVisible(true);
			}
		});
	}	
}
