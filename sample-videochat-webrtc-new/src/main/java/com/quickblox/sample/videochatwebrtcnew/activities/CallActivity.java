package com.quickblox.sample.videochatwebrtcnew.activities;


import android.app.Fragment;
import android.app.FragmentManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import com.quickblox.chat.QBChatService;
import com.quickblox.chat.QBSignaling;
import com.quickblox.chat.QBWebRTCSignaling;
import com.quickblox.chat.listeners.QBVideoChatSignalingManagerListener;
import com.quickblox.sample.videochatwebrtcnew.ApplicationSingleton;
import com.quickblox.sample.videochatwebrtcnew.R;
import com.quickblox.sample.videochatwebrtcnew.User;
import com.quickblox.sample.videochatwebrtcnew.adapters.OpponentsAdapter;
import com.quickblox.sample.videochatwebrtcnew.definitions.Consts;
import com.quickblox.sample.videochatwebrtcnew.fragments.ConversationFragment;
import com.quickblox.sample.videochatwebrtcnew.fragments.IncomeCallFragment;
import com.quickblox.sample.videochatwebrtcnew.fragments.OpponentsFragment;
import com.quickblox.sample.videochatwebrtcnew.fragments.ScreenCaptiringFragment;
import com.quickblox.sample.videochatwebrtcnew.holder.DataHolder;
import com.quickblox.sample.videochatwebrtcnew.util.ReflectionUtils;
import com.quickblox.sample.videochatwebrtcnew.util.RingtonePlayer;
import com.quickblox.sample.videochatwebrtcnew.util.SettingsUtil;
import com.quickblox.users.model.QBUser;
import com.quickblox.videochat.webrtc.QBRTCClient;
import com.quickblox.videochat.webrtc.QBRTCConfig;
import com.quickblox.videochat.webrtc.QBRTCException;
import com.quickblox.videochat.webrtc.QBRTCSession;
import com.quickblox.videochat.webrtc.QBRTCTypes;
import com.quickblox.videochat.webrtc.callbacks.QBRTCClientSessionCallbacks;
import com.quickblox.videochat.webrtc.callbacks.QBRTCClientVideoTracksCallbacks;
import com.quickblox.videochat.webrtc.callbacks.QBRTCSessionConnectionCallbacks;

import org.jivesoftware.smack.SmackException;
import org.webrtc.VideoCapturerAndroid;
import org.webrtc.VideoRendererGui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Created by tereha on 16.02.15.
 */
public class CallActivity extends BaseLogginedUserActivity implements QBRTCClientSessionCallbacks, QBRTCSessionConnectionCallbacks {


    private static final String TAG = CallActivity.class.getSimpleName();
    private static final String ADD_OPPONENTS_FRAGMENT_HANDLER = "opponentHandlerTask";
    private static final long TIME_BEGORE_CLOSE_CONVERSATION_FRAGMENT = 3;
    private static final String INCOME_WINDOW_SHOW_TASK_THREAD = "INCOME_WINDOW_SHOW";

    public static final String OPPONENTS_CALL_FRAGMENT = "opponents_call_fragment";
    public static final String INCOME_CALL_FRAGMENT = "income_call_fragment";
    public static final String CONVERSATION_CALL_FRAGMENT = "conversation_call_fragment";
    public static final String CALLER_NAME = "caller_name";
    public static final String SESSION_ID = "sessionID";
    public static final String START_CONVERSATION_REASON = "start_conversation_reason";


    private QBRTCSession currentSession;
    public  static String login;
    public  List<User> opponentsList;
    private Runnable showIncomingCallWindowTask;
    private Handler showIncomingCallWindowTaskHandler;
    private BroadcastReceiver wifiStateReceiver;
    private boolean closeByWifiStateAllow = true;
    private String hangUpReason;
    private boolean isInCommingCall;
    private boolean isInFront;
    private QBRTCClient rtcClient;
    private QBRTCSessionUserCallback sessionUserCallback;
    private boolean wifiEnabled = true;
    private SharedPreferences sharedPref;
    private RingtonePlayer ringtonePlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        opponentsList= DataHolder.getUsers();

        Log.d(TAG, "Activity. Thread id: " + Thread.currentThread().getId());

        // Probably initialize members with default values for a new instance
        login = getIntent().getStringExtra("login");

        if (savedInstanceState == null) {
            addOpponentsFragment();
        }

        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        initQBRTCClient();
        initWiFiManagerListener();
        ringtonePlayer = new RingtonePlayer(this);
    }

    private void initQBRTCClient() {
        rtcClient = QBRTCClient.getInstance(this);
        // Add signalling manager
        QBChatService.getInstance().getVideoChatWebRTCSignalingManager().addSignalingManagerListener(new QBVideoChatSignalingManagerListener() {
            @Override
            public void signalingCreated(QBSignaling qbSignaling, boolean createdLocally) {
                if (!createdLocally) {
                    rtcClient.addSignaling((QBWebRTCSignaling) qbSignaling);
                }
            }
        });

        rtcClient.setCameraErrorHendler(new VideoCapturerAndroid.CameraErrorHandler() {
            @Override
            public void onCameraError(final String s) {
                CallActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(CallActivity.this, s, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });

        QBRTCConfig.setMaxOpponentsCount(6);
        QBRTCConfig.setDisconnectTime(40);
        QBRTCConfig.setAnswerTimeInterval(30l);
        QBRTCConfig.setDebugEnabled(true);
        // Add activity as callback to RTCClient
        rtcClient.addSessionCallbacksListener(this);
        // Start mange QBRTCSessions according to VideoCall parser's callbacks
        rtcClient.prepareToProcessCalls();
    }

    private void initWiFiManagerListener() {
        wifiStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "WIFI was changed");
                processCurrentWifiState(context);
            }
        };
    }

    private void processCurrentWifiState(Context context) {
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifiEnabled != wifi.isWifiEnabled()) {
            wifiEnabled = wifi.isWifiEnabled();
            showToast("Wifi " + (wifiEnabled ? "enabled" : "disabled"));
        }
    }

    private void disableConversationFragmentButtons() {
        ConversationFragment fragment = (ConversationFragment) getFragmentManager().findFragmentByTag(CONVERSATION_CALL_FRAGMENT);
        if (fragment != null) {
            fragment.actionButtonsEnabled(false);
        }
    }


    private void initIncommingCallTask() {
        showIncomingCallWindowTaskHandler = new Handler(Looper.myLooper());
        showIncomingCallWindowTask = new Runnable() {
            @Override
            public void run() {
                IncomeCallFragment incomeCallFragment = (IncomeCallFragment) getFragmentManager().findFragmentByTag(INCOME_CALL_FRAGMENT);
                if (incomeCallFragment == null) {
                    ConversationFragment conversationFragment = (ConversationFragment) getFragmentManager().findFragmentByTag(CONVERSATION_CALL_FRAGMENT);
                    if (conversationFragment != null) {
                        disableConversationFragmentButtons();
                        ringtonePlayer.stopOutBeep();
                        hangUpCurrentSession();
                    }
                } else {
                    rejectCurrentSession();
                }
                Toast.makeText(CallActivity.this, "Call was stopped by timer", Toast.LENGTH_LONG).show();
            }
        };
    }

    public void rejectCurrentSession() {
        if (getCurrentSession() != null) {
            getCurrentSession().rejectCall(new HashMap<String, String>());
        }
    }

    public void hangUpCurrentSession() {
        ringtonePlayer.stopOutBeep();
        if (getCurrentSession() != null) {
            getCurrentSession().hangUp(new HashMap<String, String>());
        }
    }

    private void startIncomeCallTimer() {
        showIncomingCallWindowTaskHandler.postAtTime(showIncomingCallWindowTask, SystemClock.uptimeMillis() + TimeUnit.SECONDS.toMillis(QBRTCConfig.getAnswerTimeInterval()));
    }

    private void stopIncomeCallTimer() {
        Log.d(TAG, "stopIncomeCallTimer");
        showIncomingCallWindowTaskHandler.removeCallbacks(showIncomingCallWindowTask);
    }


    @Override
    protected void onStart() {
        super.onStart();

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        registerReceiver(wifiStateReceiver, intentFilter);


    }

    @Override
    protected void onResume() {
        isInFront = true;

        if (currentSession == null) {
            addOpponentsFragment();
        }
        super.onResume();
    }

    @Override
    protected void onPause() {
        isInFront = false;
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(wifiStateReceiver);
    }

    public QBRTCSession getCurrentSession() {
        return currentSession;
    }

    private void forbidenCloseByWifiState() {
        closeByWifiStateAllow = false;
    }


    public void setCurrentSession(QBRTCSession sesion) {
        this.currentSession = sesion;
    }

    // ---------------Chat callback methods implementation  ----------------------//

    @Override
    public void onReceiveNewSession(final QBRTCSession session) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {

                Log.d(TAG, "Session " + session.getSessionID() + " are income");
                String curSession = (getCurrentSession() == null) ? null : getCurrentSession().getSessionID();

                if (getCurrentSession() == null) {
                    Log.d(TAG, "Start new session");
                    setCurrentSession(session);
                    session.addSessionCallbacksListener(CallActivity.this);
                    addIncomeCallFragment(session);

                    isInCommingCall = true;
                    initIncommingCallTask();
                    startIncomeCallTimer();
                } else {
                    Log.d(TAG, "Stop new session. Device now is busy");
                    session.rejectCall(null);
                }

            }
        });
    }

    @Override
    public void onUserNotAnswer(QBRTCSession session, Integer userID) {
        if (!session.equals(getCurrentSession())) {
            return;
        }
        if (sessionUserCallback != null) {
            sessionUserCallback.onUserNotAnswer(session, userID);
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                    ringtonePlayer.stopOutBeep();
            }
        });
    }

    @Override
    public void onStartConnectToUser(QBRTCSession session, Integer userID) {

    }

    @Override
    public void onCallAcceptByUser(QBRTCSession session, Integer userId, Map<String, String> userInfo) {
        if (!session.equals(getCurrentSession())) {
            return;
        }
        if (sessionUserCallback != null) {
            sessionUserCallback.onCallAcceptByUser(session, userId, userInfo);
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ringtonePlayer.stopOutBeep();
            }
        });
    }

    @Override
    public void onCallRejectByUser(QBRTCSession session, Integer userID, Map<String, String> userInfo) {
        if (!session.equals(getCurrentSession())) {
            return;
        }
        if (sessionUserCallback != null) {
            sessionUserCallback.onCallRejectByUser(session, userID, userInfo);
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ringtonePlayer.stopOutBeep();
            }
        });
    }

    @Override
    public void onConnectionClosedForUser(QBRTCSession session, Integer userID) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Close app after session close of network was disabled
                if (hangUpReason != null && hangUpReason.equals(Consts.WIFI_DISABLED)) {
                    Intent returnIntent = new Intent();
                    setResult(Consts.CALL_ACTIVITY_CLOSE_WIFI_DISABLED, returnIntent);
                    finish();
                }
            }
        });
    }

    @Override
    public void onConnectedToUser(QBRTCSession session, final Integer userID) {
        forbidenCloseByWifiState();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isInCommingCall) {
                    stopIncomeCallTimer();
                }

                startTimer();
                Log.d(TAG, "onConnectedToUser() is started");

            }
        });
    }


    @Override
    public void onDisconnectedTimeoutFromUser(QBRTCSession session, Integer userID) {

    }

    @Override
    public void onConnectionFailedWithUser(QBRTCSession session, Integer userID) {

    }

    @Override
    public void onError(QBRTCSession qbrtcSession, QBRTCException e) {
    }

    @Override
    public void onSessionClosed(final QBRTCSession session) {

        runOnUiThread(new Runnable() {
            @Override
            public void run() {

                Log.d(TAG, "Session " + session.getSessionID() + " start stop session");
                String curSession = (getCurrentSession() == null) ? null : getCurrentSession().getSessionID();

                if (session.equals(getCurrentSession())) {

                    Fragment currentFragment = getCurrentFragment();
                    if (isInCommingCall) {
                        stopIncomeCallTimer();
                        if (currentFragment instanceof IncomeCallFragment) {
                            removeIncomeCallFragment();
                        }
                    }

                    Log.d(TAG, "Stop session");
                    if (!(currentFragment instanceof OpponentsFragment)) {
                        addOpponentsFragment();
                    }

                    currentSession = null;

                    stopTimer();
                    closeByWifiStateAllow = true;
                }
            }
        });
    }

    @Override
    public void onSessionStartClose(final QBRTCSession session) {
        session.removeSessionnCallbacksListener(CallActivity.this);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ConversationFragment fragment = (ConversationFragment) getFragmentManager().findFragmentByTag(CONVERSATION_CALL_FRAGMENT);
                if (fragment != null && session.equals(getCurrentSession())) {
                    fragment.actionButtonsEnabled(false);
                }
            }
        });
    }

    @Override
    public void onDisconnectedFromUser(QBRTCSession session, Integer userID) {

    }

    private void showToast(final int message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(CallActivity.this, getString(message), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showToast(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(CallActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onReceiveHangUpFromUser(final QBRTCSession session, final Integer userID) {
        if (session.equals(getCurrentSession())) {

            if (sessionUserCallback != null) {
                sessionUserCallback.onReceiveHangUpFromUser(session, userID);
            }

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    showToast(getString(R.string.hungUp) + "from user "+userID);
                }
            });
        }
    }


    private Fragment getCurrentFragment(){
        return getFragmentManager().findFragmentById(R.id.fragment_container);
    }

    public void addOpponentsFragment() {
        if (isInFront) {
            getFragmentManager().beginTransaction().replace(R.id.fragment_container, new OpponentsFragment(), OPPONENTS_CALL_FRAGMENT).commit();
        }
    }


    public void removeIncomeCallFragment() {
        FragmentManager fragmentManager = getFragmentManager();
        Fragment fragment = fragmentManager.findFragmentByTag(INCOME_CALL_FRAGMENT);

        if (fragment != null) {
            fragmentManager.beginTransaction().remove(fragment).commit();
        }
    }

    private void addIncomeCallFragment(QBRTCSession session) {

        Log.d(TAG, "QBRTCSession in addIncomeCallFragment is " + session);
        if (session != null && isInFront) {
            Fragment fragment = new IncomeCallFragment();
            Bundle bundle = new Bundle();
            bundle.putSerializable("sessionDescription", session.getSessionDescription());
            bundle.putIntegerArrayList("opponents", new ArrayList<>(session.getOpponents()));
            bundle.putInt(ApplicationSingleton.CONFERENCE_TYPE, session.getConferenceType().getValue());
            fragment.setArguments(bundle);
            getFragmentManager().beginTransaction().replace(R.id.fragment_container, fragment, INCOME_CALL_FRAGMENT).commit();
        } else {
            Log.d(TAG, "SKIP addIncomeCallFragment method");
        }
    }

    public void addConversationFragmentStartCall(List<User> opponents,
                                                 QBRTCTypes.QBConferenceType qbConferenceType,
                                                 Map<String, String> userInfo) {
        QBRTCSession newSessionWithOpponents = rtcClient.createNewSessionWithOpponents(
                getOpponentsIds(opponents), qbConferenceType);
        SettingsUtil.setSettingsStrategy(opponents,
                getDefaultSharedPrefs(),
                this);
        Log.d("Crash", "addConversationFragmentStartCall. Set session " + newSessionWithOpponents);
        setCurrentSession(newSessionWithOpponents);
        getCurrentSession().addSessionCallbacksListener(this);
        ConversationFragment fragment = ConversationFragment.newInstance(opponents, opponents.get(0).getFullName(),
                qbConferenceType, userInfo,
                StartConversetionReason.OUTCOME_CALL_MADE, getCurrentSession().getSessionID());
        getFragmentManager().beginTransaction().replace(R.id.fragment_container, fragment, CONVERSATION_CALL_FRAGMENT).commitAllowingStateLoss();
        ringtonePlayer.startOutBeep();
    }


    public static ArrayList<Integer> getOpponentsIds(List<User> opponents) {
        ArrayList<Integer> ids = new ArrayList<Integer>();
        for (QBUser user : opponents) {
            ids.add(user.getId());
        }
        return ids;
    }


    public void addConversationFragmentReceiveCall() {

        QBRTCSession session = getCurrentSession();

        if (getCurrentSession() != null) {
            Integer myId = QBChatService.getInstance().getUser().getId();
            ArrayList<Integer> opponentsWithoutMe = new ArrayList<>(session.getOpponents());
            opponentsWithoutMe.remove(new Integer(myId));
            opponentsWithoutMe.add(session.getCallerID());

            ArrayList<User> opponents = DataHolder.getUsersByIDs(opponentsWithoutMe.toArray(new Integer[opponentsWithoutMe.size()]));
            SettingsUtil.setSettingsStrategy(opponents, getDefaultSharedPrefs(), this);
            ConversationFragment fragment = ConversationFragment.newInstance(opponents,
                    DataHolder.getUserNameByID(session.getCallerID()),
                    session.getConferenceType(), session.getUserInfo(),
                    StartConversetionReason.INCOME_CALL_FOR_ACCEPTION, getCurrentSession().getSessionID());
            // Start conversation fragment
            getFragmentManager().beginTransaction().replace(R.id.fragment_container, fragment, CONVERSATION_CALL_FRAGMENT).commit();
        }
    }


    public void setOpponentsList(List<User> qbUsers) {
        this.opponentsList = qbUsers;
    }

    public List<User> getOpponentsList() {
        return opponentsList;
    }

    public void addVideoTrackCallbacksListener(QBRTCClientVideoTracksCallbacks videoTracksCallbacks) {
        if (currentSession != null){
            currentSession.addVideoTrackCallbacksListener(videoTracksCallbacks);
        }
    }

    public void addTCClientConnectionCallback(QBRTCSessionConnectionCallbacks clientConnectionCallbacks) {
        if (currentSession != null) {
            currentSession.addSessionCallbacksListener(clientConnectionCallbacks);
        }
    }

    public void removeRTCClientConnectionCallback(QBRTCSessionConnectionCallbacks clientConnectionCallbacks) {
        if (currentSession != null) {
            currentSession.removeSessionnCallbacksListener(clientConnectionCallbacks);
        }
    }

    public void addRTCSessionUserCallback(QBRTCSessionUserCallback sessionUserCallback) {
        this.sessionUserCallback = sessionUserCallback;
    }

    public void removeRTCSessionUserCallback(QBRTCSessionUserCallback sessionUserCallback) {
        this.sessionUserCallback = null;
    }

    public void showSettings() {
       SettingsActivity.start(this);
    }

    public SharedPreferences getDefaultSharedPrefs() {
        return sharedPref;
    }

    public static enum StartConversetionReason {
        INCOME_CALL_FOR_ACCEPTION,
        OUTCOME_CALL_MADE;
    }

    @Override
    public void onBackPressed() {
        // Logout on back btn click
        Fragment fragment = getFragmentManager().findFragmentByTag(CONVERSATION_CALL_FRAGMENT);
        if (fragment == null) {
            super.onBackPressed();
            if (QBChatService.isInitialized()) {
                try {
                    rtcClient.destroy();
                    QBChatService.getInstance().logout();
                } catch (SmackException.NotConnectedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        opponentsList = null;
        OpponentsAdapter.i = 0;
    }

    public interface QBRTCSessionUserCallback {
        void onUserNotAnswer(QBRTCSession session, Integer userId);

        void onCallRejectByUser(QBRTCSession session, Integer userId, Map<String, String> userInfo);

        void onCallAcceptByUser(QBRTCSession session, Integer userId, Map<String, String> userInfo);

        void onReceiveHangUpFromUser(QBRTCSession session, Integer userId);
    }
}

