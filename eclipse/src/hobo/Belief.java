package hobo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

public class Belief {
	private final Random random;

	// this keeps track of which card is where.  the open deck and discard
	// pile are assumed to be visible, as are the size of the deck and of
	// the players' hands.
	// we get partial knowledge about the players' cards by looking at the
	// cards they draw from the open deck.  we get partial knowledge about
	// the content of the deck when the discard pile is reshuffled and
	// becomes the deck.
	// we lose knowledge when a player draws a card from the closed deck.
	// when sampling, we construct the deck and players' hands by first
	// taking the bag of cards that we know for certain are in there, and
	// then add cards from cards_of_unknown_location such that the sampled
	// hand has the same size as the real hand.
	// we could use the observations we get more efficiently by keeping,
	// for each card in some hidden bag, a probability distribution over
	// the colors.  this would greatly complicate likelihood calculation and
	// sampling.
	// the present less accurate way actually works surprisingly well, so
	// it's probably good enough.  it works well because the players tend
	// to draw lots of open cards, keep small hands, and often end up with
	// empty hands after claiming some railway.  in a 1v1 game where one of
	// the players' hands is visible, the other player's hand being empty
	// means the location of each card is known, and the likelihood of
	// reality is 1 (at least as far as cards are concerned).
	
	// for missions, we infer a probability distribution over the whereabouts
	// of each mission.  a mission can either be in the mission deck or in
	// possession of one of the players.  the inference of the distribution
	// is wildly heuristic.

	// finally, we make no attempt to infer the random state, so in sample,
	// a random random state is chosen.

	private int player; // beliefs of which player?
	
	private PlayerBelief[] players;
	
	private CardBag known_deck_cards;
	private CardBag cards_of_unknown_location;

	// matrix containing a score for each player/mission pair that roughly
	// denotes how likely a player is to have that mission.  this is used
	// to construct a joint probability matrix for sampling and likelihood
	// calculation.
	private double[][] missions;

	private List<Event> events;

	public Belief(int player) {
		this.random = new Random();
		this.events = new ArrayList<Event>();
		this.player = player;
	}

	public void initialize(State s) {
		cards_of_unknown_location = State.INITIAL_DECK.clone();
		cards_of_unknown_location.removeAll(s.open_deck);
		cards_of_unknown_location.removeAll(s.discarded); // usually empty at this point
		known_deck_cards = new CardBag();

		players = new PlayerBelief[s.players().length];
		for (int i = 0; i < players.length; i++)
			players[i] = new PlayerBelief(s.playerState(i));

		missions = new double[Mission.all.length][players.length];
		for (Mission m: Mission.all) {
			double[] ps = missions[m.ordinal()];
			// a priori, all missions are equally likely to be in
			// possession of any player or in the deck.  use some
			// small positive number.
			for (int i = 0; i < players.length; i++)
				ps[i] = 1e-3;
			// except the ones we have ourselves, of course
			if (s.playerState(player).missions.contains(m))
				ps[player] = Double.POSITIVE_INFINITY;
		}
	}

	public void update(Event e) {
		events.add(e);
		
		Decision d = e.decision;
		     if (d instanceof     DrawCardDecision) update((    DrawCardDecision)d, (DrawCardDecision.AppliedDecision)e.applied_decision, e.state);
		else if (d instanceof ClaimRailwayDecision) update((ClaimRailwayDecision)d, (ClaimRailwayDecision.AppliedDecision)e.applied_decision, e.state);
		else if (d instanceof DrawMissionsDecision) update((DrawMissionsDecision)d, (DrawMissionsDecision.AppliedDecision)e.applied_decision, e.state);
		else if (d instanceof KeepMissionsDecision) update((KeepMissionsDecision)d, (KeepMissionsDecision.AppliedDecision)e.applied_decision, e.state);
		else throw new RuntimeException("unknown decision type: "+d);

		requireSanity(e.state);
	}

	public void update(DrawCardDecision d, DrawCardDecision.AppliedDecision ad, State s) {
		// do we know which color card was drawn?
		if (d.player == player || d.color != null) {
			Color c = ad.drawn_card;
			players[d.player].known_cards.add(c);

			if (d.color == null) {
				if (known_deck_cards.contains(c))
					known_deck_cards.remove(c);
				else
					cards_of_unknown_location.remove(c);
			}
		} else {
			// don't know what color -- could have been any one of them,
			// so we lose a lot of information
			for (Color c: Color.all) {
				if (known_deck_cards.contains(c)) {
					known_deck_cards.remove(c);
					cards_of_unknown_location.add(c);
				}
			}
		}
		
		handleDeckRestoration(s);
	}
	
	public void update(ClaimRailwayDecision d, ClaimRailwayDecision.AppliedDecision ad, State s) {
		// the cards that were used but not known to be in the hand
		// are now known to be in the discard pile (which is visible)
		CardBag revealed_from_hand = d.cards.clone();
		revealed_from_hand.removeAll(players[d.player].known_cards);
		cards_of_unknown_location.removeAll(revealed_from_hand);

		players[d.player].known_cards.removeAll(d.cards);

		handleDeckRestoration(s);
		
		for (Mission m: Mission.all)
			missions[m.ordinal()][d.player] += d.railway.relevanceFor(m);
	}
	
	public void update(DrawMissionsDecision d, DrawMissionsDecision.AppliedDecision ad, State s) {
	}

	public void update(KeepMissionsDecision d, KeepMissionsDecision.AppliedDecision ad, State s) {
		if (d.player == player) {
			for (Mission m: ad.drawn_missions) {
				double[] ps = missions[m.ordinal()];

				// the missions drawn but not kept are certainly in the
				// deck at this point, but not forever.  just set the
				// score to 0 everywhere; this corresponds to concluding
				// that all the railway claiming that happened before
				// this decision was not relevant to any of these
				// missions.  future claiming will be counted as usual.
				// actually, scratch that, if we set it to 0 we might
				// have trouble sampling (because the probability ends
				// up 0).  just use some small positive number.
				for (int i = 0; i < players.length; i++)
					ps[i] = 1e-3;

				// the kept missions are certainly ours
				if (d.missions.contains(m))
					ps[player] = Double.POSITIVE_INFINITY;
			}
		}
	}

	private void handleDeckRestoration(State s) {
		// discard pile went back into the deck?
		CardBag discarded = s.discardeds.getFirst(); 
		if (discarded != null)
			known_deck_cards.addAll(discarded);

		// new cards in open deck?
		CardBag restoration = s.deck_restorations.getFirst(); 
		if (restoration != null) {
			// cards that went into the open deck but were not known
			// to be in the normal deck are now known to be in the
			// open deck (which is visible)
			CardBag revealed = restoration.clone();
			revealed.removeAll(known_deck_cards);
			cards_of_unknown_location.removeAll(revealed);

			known_deck_cards.removeAll(restoration);
		}
	}
	
	// return a possible state according to the distribution defined by this belief
	public State sample(State s) {
		// sample cards
		CardBag unknown = cards_of_unknown_location.clone();
		s = s.clone();
		//s.random = new Random(random.nextLong());

		CardBag deck = known_deck_cards.clone();
		deck.addAll(unknown.remove_sample(s.deck.size() - known_deck_cards.size(), random));
		s.deck = deck;

		for (int i = 0; i < players.length; i++) {
			if (i == player)
				continue;
			PlayerState ps = s.playerState(i);
			PlayerBelief pb = players[i];
			CardBag hand = pb.known_cards.clone();
			hand.addAll(unknown.remove_sample(ps.hand.size() - hand.size(), random));
			ps.hand = hand;
		}

		assert(unknown.isEmpty());

		// sample missions

		// determine how many missions we need to sample for each player
		int[] ns = new int[players.length];
		for (int i = 0; i < players.length; i++) {
			PlayerState ps = s.playerState(i);
			ns[i] = ps.missions.size();
			ps.missions = EnumSet.noneOf(Mission.class);
		}

		// players' missions will be removed from this as we go along
		s.missions = EnumSet.allOf(Mission.class);

		// get a working copy of this matrix
		double[][] missions = Util.clone(this.missions);
		sampling: while (true) {
			// should terminate?
			boolean samples_needed = false;
			for (int i = 0; i < players.length; i++) {
				if (ns[i] > 0) {
					samples_needed = true;
					break;
				}
			}
			if (!samples_needed)
				break sampling;

			// determine non-normalized joint probability distribution
			double total = 0;
			double[][] jpd = Util.clone(missions);
			for (int i = 0; i < jpd.length; i++) {
				for (int j = 0; j < jpd[i].length; j++) {
					// more probability to the players that need more missions
					jpd[i][j] *= ns[j];
					total += jpd[i][j];
				}
			}
			if (total < 0) {
				System.err.println("non-normalized probability distribution sums to x < 0");
				System.err.println("distribution: "+Arrays.deepToString(jpd));
				throw new RuntimeException();
			}

			// now draw a sample from the jpd
			double x = random.nextDouble() * total;
			for (int i = 0; i < jpd.length; i++) {
				for (int j = 0; j < jpd[i].length; j++) {
					x -= jpd[i][j];
					// positive infinity signifies certainty but will result in
					// x being NaN, so special-case it
					if (x <= 0 || jpd[i][j] == Double.POSITIVE_INFINITY) {
						Mission m = Mission.all[i];
						s.missions.remove(m);
						s.playerState(j).missions.add(m);
						ns[j]--; // one fewer mission needed for this player
						// this mission will no longer be sampled
						for (int k = 0; k < players.length; k++)
							missions[i][k] = 0;
						continue sampling;
					}
				}
			}

			// shouldn't get here
			System.err.println(x+" of mass left. distribution:");
			System.err.println(Arrays.deepToString(jpd));
			throw new RuntimeException();
		}

		return s;
	}

	// return the likelihood (a probability) of a state given that this belief is true
	public double likelihoodOf(State s) {
		double p = 1;

		// cards
		CardBag unknown = cards_of_unknown_location.clone();

		CardBag unknown_deck_cards = s.deck.clone();
		assert(unknown_deck_cards.containsAll(known_deck_cards));
		unknown_deck_cards.removeAll(known_deck_cards);

		p *= unknown.probabilityOfSample(unknown_deck_cards);
		unknown.removeAll(unknown_deck_cards);

		for (int i = 0; i < players.length; i++) {
			if (i == player)
				continue;

			CardBag unknown_hand_cards = s.playerState(i).hand.clone();
			assert(unknown_hand_cards.containsAll(players[i].known_cards));
			unknown_hand_cards.removeAll(players[i].known_cards);

			p *= unknown.probabilityOfSample(unknown_hand_cards);
			unknown.removeAll(unknown_hand_cards);
		}
		
		// missions (this stuff is GROSS)

		// get a working copy of this matrix
		double[][] missions = Util.clone(this.missions);

		// determine how many missions we need to sample for each player
		int[] ns = new int[players.length];
		// collect the player/mission pairs we want (think 2D enumset)
		boolean[][] pairs = new boolean[missions.length][players.length];
		for (int j = 0; j < players.length; j++) {
			for (Mission m: s.playerState(j).missions) {
				int i = m.ordinal();
				if (missions[i][j] == Double.POSITIVE_INFINITY) {
					// we are certain that this mission is with this player
					for (int k = 0; k < players.length; k++)
						missions[i][k] = 0;
				} else {
					ns[j]++;
					pairs[i][j] = true;
				}
			}
		}

		for (int i = 0; i < pairs.length; i++) {
			for (int j = 0; j < pairs[i].length; j++) {
				if (pairs[i][j]) {
					// determine non-normalized joint probability distribution
					double total = 0;
					double[][] jpd = Util.clone(missions);
					for (int k = 0; k < jpd.length; k++) {
						for (int l = 0; l < jpd[k].length; l++) {
							// more probability to the players that need more missions
							jpd[k][l] *= ns[l];
							total += jpd[k][l];
							if (Double.isNaN(total)) {
								System.out.println("total just became NaN.  last term: "+jpd[k][l]+" was multiplied by "+ns[l]);
								System.out.println("original distribution: "+Arrays.deepToString(this.missions));
								throw new RuntimeException();
							}							
						}
					}

					p *= jpd[i][j] / total;
					if (Double.isNaN(p)) {
						System.out.println("p just became NaN.  last factor: "+jpd[i][j]+"/"+total);
						System.out.println("original distribution: "+Arrays.deepToString(this.missions));
						throw new RuntimeException();
					}
					
					ns[j]--; // one fewer mission needed for this player
					// this mission will no longer be sampled
					for (int k = 0; k < players.length; k++)
						missions[i][k] = 0;
				}
			}
		}

		return p;
	}

	public void requireSanity(State s) {
		boolean sane = true;
		
		if (s.players().length != players.length) {
			System.err.println("inconsistent belief: number of players changed");
			sane = false;
		}
		
		if (!s.deck.containsAll(known_deck_cards)) {
			CardBag surplus = known_deck_cards.clone();
			surplus.removeAll(s.deck);
			System.err.println("inconsistent belief: known_deck_cards "+known_deck_cards+" contains cards "+surplus+" that are not in the deck "+s.deck);
			sane = false;
		}
		for (int i = 0; i < players.length; i++) {
			if (!s.playerState(i).hand.containsAll(players[i].known_cards)) {
				System.err.println("inconsistent belief: for player "+s.playerState(i).name+", known_cards "+players[i].known_cards+" contains cards that are not in the hand "+s.playerState(i).hand);
				sane = false;
			}
		}

		int total_card_count = 0;
		total_card_count = cards_of_unknown_location.size();
		total_card_count += known_deck_cards.size();
		total_card_count += s.open_deck.size();
		total_card_count += s.discarded.size();
		for (int i = 0; i < players.length; i++)
			total_card_count += players[i].known_cards.size();
		if (total_card_count != State.INITIAL_DECK.size()) {
			System.err.println("inconsistent belief: "+total_card_count+" cards in the game, should be "+State.INITIAL_DECK.size());
			sane = false;
		}

		if (!sane) {
			System.err.println("events that led to this belief (most recent event last):");
			for (Event e: events)
				System.err.println("  "+e);
			throw new RuntimeException("inconsistent belief");
		}
	}
	
	private class PlayerBelief {
		private CardBag known_cards;

		public PlayerBelief(PlayerState s) {
			if (s.handle == player) {
				known_cards = s.hand.clone();
				cards_of_unknown_location.removeAll(known_cards);
			} else {
				known_cards = new CardBag();
			}
		}
	}
}