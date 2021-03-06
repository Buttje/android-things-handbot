package com.example.sewl.androidthingssample;

import android.graphics.Color;
import android.os.Handler;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by mderrick on 10/11/17.
 */

public class RockPaperScissors implements Game {

    private static final String TAG                    = RockPaperScissors.class.getSimpleName();

    public static final int MONITOR_TIME               = 2000;
    private static final long WAIT_FOR_NEW_ROUND_DELAY = 800;
    private static final long START_ROUND_TIME         = 1500;
    private static final int ORANGE                    = 0xFFA500;

    private static final long ANIMATION_WAIT_TIME      = 3000;
    public static final int COUNTDOWN_DELAY_MILLIS     = 2200;

    private GameStateListener gameStateListener;

    private SoundController soundController;

    private HandController handController;

    private LightRingControl lightRingControl;

    private Map<String, Integer> monitoredActions = new HashMap<>();

    private int roundLosses;

    private int roundWins;

    private long timeToTransition = System.currentTimeMillis();

    private String[] ACTIONS = new String[] { Signs.ROCK, Signs.PAPER, Signs.SCISSORS };

    private String thrownAction;

    private Thread rpsThread;

    private int noInputFoundRounds = 0;

    public RockPaperScissors(HandController handController, GameStateListener gameStateListener,
                             LightRingControl lightRingControl, SoundController soundController) {
        this.handController = handController;
        this.gameStateListener = gameStateListener;
        this.lightRingControl = lightRingControl;
        this.soundController = soundController;
    }

    private enum States {
        IDLE,
        INITIATE,
        INITIATE_WAIT,
        COUNTDOWN,
        THROW_WAIT,
        MONITOR,
        DETERMINE_ROUND_WINNER,
        WIN,
        LOSS,
        WAIT_FOR_NEW_ROUND,
        WAIT_FOR_NEW_GAME,
        END_GAME,
        END_GAME_WAIT,
        GAME_OVER
    }

    private enum GAME_RESULTS {
        WIN,
        LOSS,
        TIE,
        NO_INPUT
    }

    private States currentState = States.IDLE;

    @Override
    public void shutdown() {
        handController = null;
        gameStateListener = null;
    }

    @Override
    public void start() {
        if (lightRingControl!=null) {
            lightRingControl.runSwirl(1, Color.BLUE);
        }
        handController.moveToRPSReady();
        soundController.playSound(SoundController.Sounds.START_GAME);
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                currentState = States.INITIATE;
            }
        }, 1000);
    }

    @Override
    public void stop() {
        currentState = States.IDLE;
    }

    @Override
    public void run(String seenAction, List<Classifier.Recognition> results) {
        switch (currentState) {
            case IDLE:
                resetGame();
                break;
            case INITIATE:
                resetRound();
                setTransitionTime(START_ROUND_TIME);
                currentState = States.INITIATE_WAIT;
                break;
            case INITIATE_WAIT:
                currentState = nextStateForWaitState(States.COUNTDOWN);
                break;
            case COUNTDOWN:
                lightRingControl.setRPSScore(roundWins, roundLosses);
                currentState = States.THROW_WAIT;
                thrownAction = ACTIONS[(int)(Math.random() * ACTIONS.length)];
                setTransitionTime(COUNTDOWN_DELAY_MILLIS);
                runRPSCountdown();
                break;
            case THROW_WAIT:
                currentState = nextStateForWaitState(States.MONITOR);
                if (currentState == States.MONITOR) {
                    setTransitionTime(MONITOR_TIME);
                }
                break;
            case MONITOR:
                logAction(seenAction);
                currentState = nextStateForWaitState(States.DETERMINE_ROUND_WINNER);
                break;
            case DETERMINE_ROUND_WINNER:
                String userThrow = getUserThrow();
                GAME_RESULTS gameResults = getGameResults(userThrow);
                Log.i(TAG, "vs: " + thrownAction + " vs " + userThrow);
                if (gameResults == GAME_RESULTS.WIN) {
                    roundWins++;
                } else if (gameResults == GAME_RESULTS.LOSS) {
                    roundLosses++;
                } else if (gameResults == GAME_RESULTS.NO_INPUT) {
                    noInputFoundRounds++;
                }

                if (gameOver()) {
                    currentState = roundWins > roundLosses ? States.WIN : States.LOSS;
                } else {
                    setTransitionTime(WAIT_FOR_NEW_ROUND_DELAY);
                    currentState = States.WAIT_FOR_NEW_ROUND;
                    if (gameResults == GAME_RESULTS.TIE) {
                        lightRingControl.flash(1, ORANGE);
                        soundController.playSound(SoundController.Sounds.TIE);
                    } else if (gameResults == GAME_RESULTS.WIN) {
                        soundController.playSound(SoundController.Sounds.ROUND_WIN);
                    } else if (gameResults == GAME_RESULTS.LOSS) {
                        soundController.playSound(SoundController.Sounds.ROUND_LOSS);
                    } else {
                        if (noInputFoundRounds >= 3) {
                            currentState = States.LOSS;
                        } else {
                            lightRingControl.flash(1, ORANGE);
                            soundController.playSound(SoundController.Sounds.TIE);
                        }
                    }
                }
                handController.moveToRPSReady();
                lightRingControl.setRPSScore(roundWins, roundLosses);
                break;
            case WIN:
                soundController.playSound(SoundController.Sounds.WIN);
                lightRingControl.runSwirl(3, Color.GREEN);
                setTransitionTime(ANIMATION_WAIT_TIME);
                currentState = States.WAIT_FOR_NEW_GAME;
                break;
            case LOSS:
                soundController.playSound(SoundController.Sounds.LOSS);
                lightRingControl.runSwirl(3, Color.RED);
                setTransitionTime(ANIMATION_WAIT_TIME);
                currentState = States.WAIT_FOR_NEW_GAME;
                break;
            case WAIT_FOR_NEW_ROUND:
                currentState = nextStateForWaitState(States.INITIATE);
                break;
            case WAIT_FOR_NEW_GAME:
                currentState = nextStateForWaitState(States.END_GAME);
                break;
            case END_GAME:
                handController.loose();
                setTransitionTime(ANIMATION_WAIT_TIME);
                currentState = States.END_GAME_WAIT;
                break;
            case END_GAME_WAIT:
                currentState = nextStateForWaitState(States.GAME_OVER);
                break;
            case GAME_OVER:
                lightRingControl.setRPSScore(0, 0);
                if (gameStateListener != null) {
                    gameStateListener.gameFinished();
                }
                currentState = States.IDLE;
                break;
        }
    }

    private void runRPSCountdown() {
        final int sleepTime = 350;
        final int pauseTime = 200;
        final int afterSoundTime = 100;

        //  Weird timing thread to handle the rps-countdown
        rpsThread = new Thread(new Runnable() {
            @Override
            public void run() {
                soundController.playSound(SoundController.Sounds.RPS_BING);
                sleep(afterSoundTime);
                handController.rpsDownCount();
                sleep(pauseTime);
                handController.moveToRPSReady();
                sleep(sleepTime);
                soundController.playSound(SoundController.Sounds.RPS_BING);
                sleep(afterSoundTime);
                handController.rpsDownCount();
                sleep(pauseTime);
                handController.moveToRPSReady();
                sleep(sleepTime);
                soundController.playSound(SoundController.Sounds.RPS_BING);
                sleep(afterSoundTime);
                handController.rpsDownCount();
                sleep(pauseTime);
                handController.moveToRPSReady();
                sleep(sleepTime);
                soundController.playSound(SoundController.Sounds.RPS_BONG);
                sleep(afterSoundTime);
                handController.handleRPSAction(thrownAction);

                if (rpsThread != null) {
                    rpsThread.interrupt();
                }
            }

            private void sleep(long time) {
                try {
                    Thread.sleep(time);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Failed to sleep on RPS process", e);
                }
            }
        });
        rpsThread.start();
    }

    @Override
    public String getClassifierKey() {
        return "rps";
    }

    private boolean gameOver() {
        return roundLosses == 3 || roundWins == 3 ||
                (roundWins + roundLosses) >= 5;
    }

    private GAME_RESULTS getGameResults(String seenAction) {
        if (seenAction == null) {
            return GAME_RESULTS.NO_INPUT;
        }

        if (seenAction.equals(Signs.ROCK)) {
            if (thrownAction.equals(Signs.ROCK)) {
                return GAME_RESULTS.TIE;
            } else if (thrownAction.equals(Signs.PAPER)) {
                return GAME_RESULTS.LOSS;
            } else {
                return GAME_RESULTS.WIN;
            }
        } else if (seenAction.equals(Signs.PAPER)) {
            if (thrownAction.equals(Signs.ROCK)) {
                return GAME_RESULTS.WIN;
            } else if (thrownAction.equals(Signs.PAPER)) {
                return GAME_RESULTS.TIE;
            } else {
                return GAME_RESULTS.LOSS;
            }
        } else if (seenAction.equals(Signs.SCISSORS)) {
            if (thrownAction.equals(Signs.ROCK)) {
                return GAME_RESULTS.LOSS;
            } else if (thrownAction.equals(Signs.PAPER)) {
                return GAME_RESULTS.WIN;
            } else {
                return GAME_RESULTS.TIE;
            }
        }
        return GAME_RESULTS.TIE;
    }

    private String getUserThrow() {
        int mostSamples = 0;
        String mostSampledAction = null;
        for (String action : ACTIONS) {
            boolean actionOccurred = monitoredActions.containsKey(action);
            if (actionOccurred && monitoredActions.get(action) > mostSamples) {
                mostSampledAction = action;
                mostSamples = monitoredActions.get(action);
            }
        }
        return mostSampledAction;
    }

    private void resetGame() {
        roundLosses = 0;
        roundWins = 0;
        noInputFoundRounds = 0;
        lightRingControl.setRPSScore(0, 0);
    }

    private void setTransitionTime(long delay) {
        timeToTransition = System.currentTimeMillis() + delay;
    }

    private void resetRound() {
        monitoredActions = new HashMap();
    }

    private States nextStateForWaitState(States nextState) {
        if (System.currentTimeMillis() >= timeToTransition) {
            return nextState;
        } else {
            return currentState;
        }
    }

    private void logAction(String seenAction) {
        if (Signs.NEGATIVE.equals(seenAction)) {
            return;
        }

        if (!monitoredActions.containsKey(seenAction)) {
            monitoredActions.put(seenAction, 0);
        }
        Integer oldValue = monitoredActions.get(seenAction);
        monitoredActions.put(seenAction, oldValue + 1);
    }
}
