package hobo;

import java.util.*;

public class Game {
	private final State state;
	private final Player[] players;
	private final List<GameObserver> observers = new ArrayList<GameObserver>();
	
	// this is useful for comparing two AIs that should behave exactly equally
	public final List<Decision> decisionSequence = new ArrayList<Decision>();

	public Game(Player... players) {
		this.players = players;

		int ni = players.length;
		String[] names = new String[ni];
		for (int i = 0; i < ni; i++) {
			names[i] = players[i].name();
			players[i].setHandle(i);
		}
		state = new State(names);
	}

	public void play() {
		state.setup();

		while (true) {
			try {
				advance();
				
				if (aborted)
					return;
				
				if (state.gameOver()) {
					if (state.isDraw()) {
						for (Player p: players)
							p.draw(state);
					} else {
						Player w = players[state.winner()];
						for (Player l: players) {
							if (l != w)
								l.loss(state);
						}
						w.win(state);
					}
					break;
				}
			} catch (IllegalDecisionException e) {
				e.printStackTrace();
			}
		}
	}

	public void advance() {
		Player p = players[state.currentPlayer()];
	
		Decision d;
		while (true) {
			d = p.decide(state);
			decisionSequence.add(d);
			System.out.println(p.name()+" decided "+d);
			if (d == null) {
				abort();
				return;
			}
			try {
				state.applyDecision(d);
				break;
			} catch (IllegalDecisionException e) {
				System.out.println("illegal decision: "+d.reasonForIllegality(state));
				p.illegal(state, d, e.reason);
			}
		}
	
		Event e = new Event(state, p, d);
		notifyPlayers(e);
		notifyObservers(e);
	}
	
	public void printScores() {
		for (Player p: players) {
			PlayerState ps = state.playerState(p.handle);
			System.out.println(ps.name+": "+ps.finalScore());
		}
	}

	private boolean aborted = false;
	public void abort() {
		aborted = true;
	}

	public void notifyPlayers(Event e) {
		for (Player p: players)
			p.perceive(e);
	}

	public void registerObserver(GameObserver go) {
		observers.add(go);
	}

	public void notifyObservers(Event e) {
		for (GameObserver go: observers)
			go.observe(e);
	}
}
