package org.za.hem.ipsec_tools;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.Toast;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.za.hem.ipsec_tools.service.NativeService;

/*
 * Register
 * android.telephony.TelephonyManager.DATA_CONNECTED
 * android.telephony.TelephonyManager.DATA_DISCONNECTED
 * 
 * Context.getSystemService(Context.CONNECTIVITY_SERVICE).
 * CONNECTIVITY_ACTION
 */

/**
 * 
 * @author mikael
 *
 */

public class IPsecToolsActivity extends PreferenceActivity
		implements OnPreferenceClickListener, OnPeerChangeListener {
	final private String binaries[] = {
			"racoon.sh",
			"racoonctl.sh",
			"setkey.sh"
 	};
    // FIXME debugging
	private final boolean DEBUG = true;

	private boolean mIsBound;
	private NotificationManager mNM;
	private NativeService mBoundService;
	private NativeCommand mNative;
	private ConfigManager mCM;
	private static final String ADD_PREFERENCE = "addPref";
	private static final String PEERS_PREFERENCE = "peersPref";
	private static final String COUNT_PREFERENCE = "countPref";
	private PeerList mPeers;
	private PeerID selectedID;
	private Peer selectedPeer;
	private PeerID mEditID;
	
    @Override
    public void onCreate(Bundle savedInstanceState) {
    	super.onCreate(savedInstanceState);
    	
    	selectedID = null;
    	selectedPeer = null;
    	mEditID = null;

    	mCM = new ConfigManager(this);

        if (DEBUG) {
        	NativeCommand.system("killall racoon");
        }

		addPreferencesFromResource(R.xml.preferences);

        mNative = new NativeCommand(this);
        for (int i=0; i < binaries.length; i++) {
        	mNative.putBinary(binaries[i]);
        }
        try {
			mNative.putZipBinaries("ipsec-tools.zip");
		} catch (IOException e) {
			throw new RuntimeException(e);
		}        
        
		Preference addPref = findPreference(ADD_PREFERENCE);
		addPref.setOnPreferenceClickListener(new OnPreferenceClickListener() {
			public boolean onPreferenceClick(Preference preference) {
                Intent settingsActivity = new Intent(getBaseContext(),
                        PeerPreferences.class);
                PeerID id = mPeers.createPeer(IPsecToolsActivity.this);
                settingsActivity.putExtra(PeerPreferences.EXTRA_ID, id.intValue());
                startActivity(settingsActivity);
				return true;
			}
		});

    	// For each id, update name
		PreferenceGroup peersPref = (PreferenceGroup)findPreference(PEERS_PREFERENCE);
    	peersPref.removeAll();
        SharedPreferences sharedPreferences =
        	getPreferenceScreen().getSharedPreferences();
        int count = sharedPreferences.getInt(COUNT_PREFERENCE,0);
        mPeers = new PeerList(count);
        mPeers.setOnPeerChangeListener(this);
        
    	Log.i("IPsecToolsActivity", "Count: " + count);
        for (int i = 0; i < count; i++) {
        	PeerID id = new PeerID(i);
        	String key = id.toString();
        	Log.i("ipsec-tools", "Add pref: " + key);
        	if (sharedPreferences.getBoolean(key, true)) {
        		StatePreference peerPref = new StatePreference(this);
        		peerPref.setKey(key);
        		peerPref.setSummary(R.string.connect_peer);
        		peerPref.setOnPreferenceClickListener(this);
        		peerPref.setWidgetLayoutResource(R.layout.peer_widget);
        		peerPref.setIconLevel(0);
            	Log.i("ipsec-tools", "Add peerPref: " + key);
        		peersPref.addPreference(peerPref);
        		mPeers.add(new Peer(this, id, peerPref));
        	} else {
        		mPeers.add(null);
        	}
    		id = id.next();
        }
		
        try {
			mCM.build(mPeers, true);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
        
        if (DEBUG) {
        	doBindService();
        }
    }
    
    protected void startService() {
    	if (mIsBound)
    		return;
    	doBindService();
    }
    
    protected void stopService() {
    	if (!mIsBound)
    		return;
    	doUnbindService();
    }
    
    /*
    protected void updatePeers() {
    	if (mBoundService == null)
    		return;
    	
    	Log.i("ipsec-tools", "updatePeers");
   		mBoundService.vpnConnect(addr);	
    }
    */

	public void onDeletePeer(Peer peer) {
		PeerID id = peer.getPeerID();
		PreferenceGroup peersPref = (PreferenceGroup)findPreference(PEERS_PREFERENCE);
		Preference peerPref = peer.getPreference();
		Log.i("IPsecToolsActivity", "Remove peerPref: " + mPeers.size() + " " + id + " " + peerPref);
		peersPref.removePreference(peerPref);
	
		// Hide peer
		SharedPreferences.Editor editor;		
		SharedPreferences sharedPreferences =
	    	getPreferenceScreen().getSharedPreferences();
		editor = sharedPreferences.edit();
		editor.putBoolean(id.toString(), false);
		editor.commit();
	}

	public void onCreatePeer(Peer peer) {
    	String key = peer.getPeerID().toString();
    	int id = peer.getPeerID().intValue();
	
    	PreferenceGroup peersPref = (PreferenceGroup)findPreference(PEERS_PREFERENCE);
        SharedPreferences sharedPreferences =
        	getPreferenceScreen().getSharedPreferences();
        // Start transaction
        SharedPreferences.Editor editor = sharedPreferences.edit();

    	StatePreference peerPref = new StatePreference(this);
    	peerPref.setKey(key);
    	peerPref.setSummary(R.string.connect_peer);
    	peerPref.setOnPreferenceClickListener(this);
    	peerPref.setWidgetLayoutResource(R.layout.peer_widget);
    	peerPref.setIconLevel(0);
    	peersPref.addPreference(peerPref);
    	peer.setPreference(peerPref);
    
    	if (id >= sharedPreferences.getInt(COUNT_PREFERENCE, 0))
    		editor.putInt(COUNT_PREFERENCE, id + 1);
        editor.putBoolean(key, true);
        editor.commit();
    }	

    protected void onStart()
    {
    	Log.i("IPsecToolsActivity", "onStart:" + this);
    	super.onStart();
    }
    
    protected void onResume()
    {
    	Log.i("IPsecToolsActivity", "onResume:" + this);
    	super.onResume();
		mNM = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);

    	IntentFilter filter = new IntentFilter();
    	filter.addAction(NativeService.ACTION_DESTROYED);
    	filter.addAction(NativeService.ACTION_PHASE1_UP);
    	filter.addAction(NativeService.ACTION_PHASE1_DOWN);
    	filter.addAction(NativeService.ACTION_SERVICE_READY);
    	registerReceiver(mReceiver, filter);
        registerForContextMenu(getListView());

		SharedPreferences sharedPreferences =
        	getPreferenceScreen().getSharedPreferences();

		for (int i=0; i < mPeers.size(); i++) {
    		PeerID id = new PeerID(i);

    		if (sharedPreferences.getBoolean(id.toString(), true)
    				&& mPeers.get(i) != null ) {
    			Peer peer = mPeers.get(i);
    			peer.onPreferenceActivityResume();
    		}
    	}

		try {
			if (mEditID != null && mEditID.isValid()) {
				Peer peer = mPeers.get(mEditID);
				if (peer != null) {
					mCM.buildPeerConfig(peer);
				}
				mCM.build(mPeers, false);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		mEditID = null;
    }
    
    protected void onPause()
    {
    	Log.i("IPsecToolsActivity", "onPause:" + this);
    	super.onPause();
    	unregisterReceiver(mReceiver);
		unregisterForContextMenu(getListView());
		mNM = null;

		SharedPreferences sharedPreferences =
        	getPreferenceScreen().getSharedPreferences();

		for (int i=0; i < mPeers.size(); i++) {
    		PeerID id = new PeerID(i);

    		if (sharedPreferences.getBoolean(id.toString(), true)
    				&& mPeers.get(i) != null ) {
    			Peer peer = mPeers.get(i);
    			peer.onPreferenceActivityPause();
    		}
    	}
    }
    
    @Override
    protected void onStop()
    {
    	Log.i("IPsecToolsActivity", "onStop:" + this);
    	super.onStop();
    }
    
    @Override
    protected void onDestroy()
    {
    	Log.i("IPsecToolsActivity", "onDestroy:" + this);
    	super.onDestroy();
    }
        
    @Override
	public void onCreateContextMenu(ContextMenu menu, View v,
			ContextMenu.ContextMenuInfo menuInfo) {
		AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo)menuInfo;
		ListView list = (ListView)v;
		Preference pref = (Preference)list.getItemAtPosition(info.position);
		
		try {
			selectedID = PeerID.fromString(pref.getKey());
		
			if (selectedID.isValid()) {
				selectedPeer = mPeers.get(selectedID);
				Log.i("ipsec-tools", "onCreateContextMenu " + info.id + " " + info.position + " " + pref + " " + selectedPeer);
		
				MenuInflater inflater = getMenuInflater();
				inflater.inflate(R.menu.peer_menu, menu);
			} else {
				selectedPeer = null;
				Log.i("ipsec-tools", "onCreateContextMenu item not found");
			}
		} catch (PeerID.KeyFormatException e) {
			Logger.getLogger(IPsecToolsActivity.class.getName()).log(
					Level.SEVERE, "onCreateContextMenu " + e);
		}
	}
	
	@Override
	public boolean onContextItemSelected(MenuItem item) {
		//AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
	
		Log.i("ipsec-tools", "onContextItemSelected " + item);
		
		switch (item.getItemId()) {
		case R.id.connect_peer:
			mPeers.connect(selectedID);
			return true;
		case R.id.disconnect_peer:
			mPeers.disconnect(selectedID);
			return true;
		case R.id.edit_peer:
			mPeers.edit(this, selectedID);
			mEditID = selectedID;
			return true;
		case R.id.delete_peer:
			mPeers.deletePeer(selectedID, this);
			return true;
		default:
			return super.onContextItemSelected(item);
	  }
	}
	
	@Override
	public void onContextMenuClosed(Menu menu) {
		selectedID = null;
	}
	
	@Override
	public boolean onPreferenceClick(Preference arg0) {
		try {
			PeerID id = PeerID.fromString(arg0.getKey());
			Log.i("ipsec-tools", "click " + id);
			mPeers.toggle(id);
			return true;
		} catch (PeerID.KeyFormatException e) {
			return false;
		}
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
	    MenuInflater inflater = getMenuInflater();
	    inflater.inflate(R.menu.options_menu, menu);
	    return true;
	}

	@Override
	public boolean onPrepareOptionsMenu (Menu menu) {
	    menu.findItem(R.id.start_service).setVisible(!mIsBound);
	    menu.findItem(R.id.stop_service).setVisible(mIsBound);
	    return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
	    // Handle item selection
	    switch (item.getItemId()) {
	    case R.id.start_service:
	        startService();
	        return true;
	    case R.id.stop_service:
	        stopService();
	        return true;
	    case R.id.preferences:
            Intent settingsActivity = new Intent(getBaseContext(),
            		Preferences.class);
            startActivity(settingsActivity);
	    	return true;
	    default:
	        return super.onOptionsItemSelected(item);
	    }
	}
	
	private void showNotification(Peer peer, int id) {
        CharSequence text = getString(id) + " " + peer.getName();
        Notification notification = new Notification(R.drawable.icon, text,
                System.currentTimeMillis());
        //notification.flags |= Notification.FLAG_AUTO_CANCEL; 
        	
        Intent intent = new Intent(this, IPsecToolsActivity.class);
        //intent.setAction(ACTION_NOTIFICATION);
        
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                intent, 0);

        notification.setLatestEventInfo(this, getText(R.string.native_service_label),
                       text, contentIntent);

        // Send the notification.
        mNM.notify(peer.getName(), R.string.notify_peer_up, notification);
    }

	
	private BroadcastReceiver mReceiver = new BroadcastReceiver() {
    	public void onReceive(Context context, Intent intent) {
            Log.i("ipsec-tools", "broadcast received: " + intent);
    		String action = intent.getAction();
    		
    		if (action.equals(NativeService.ACTION_SERVICE_READY)) {
    			mPeers.dumpIsakmpSA();
    			return;
     		} else if (action.equals(NativeService.ACTION_DESTROYED)) {
     			return;
     		}
    		
    		InetSocketAddress remote_address = (InetSocketAddress)intent.getSerializableExtra("remote_addr");
            Log.i("ipsec-tools", "onReceive remote_addr:" + remote_address);
    		if (remote_address == null)
    			throw new RuntimeException("No remote_addr in broadcastintent");
    		Peer peer = mPeers.findForRemote(remote_address);
   		
    		if (peer == null) {
                Log.i("ipsec-tools", "Unknown peer " + remote_address);
    			return;
    		}
  
    		if (action.equals(NativeService.ACTION_PHASE1_UP)) {
    			showNotification(peer, R.string.notify_peer_up);
    			peer.onPhase1Up();
    		} else if (action.equals(NativeService.ACTION_PHASE1_DOWN)) {
    			showNotification(peer, R.string.notify_peer_down);
    			peer.onPhase1Down();
    		}

    		//output("Receive destroyed");
    	}  	
    };
    
    private void onServiceUnbound() {
        mBoundService = null;
        output("Disconnected");
        mPeers.setService(null);
  	  //      Toast.makeText(Binding.this, R.string.native_service_disconnected,
	    //            Toast.LENGTH_SHORT).show();    	
    }
    
	private ServiceConnection mConnection = new ServiceConnection() {
	    public void onServiceConnected(ComponentName className, IBinder service) {
	        // This is called when the connection with the service has been
	        // established, giving us the service object we can use to
	        // interact with the service.  Because we have bound to a explicit
	        // service that we know is running in our own process, we can
	        // cast its IBinder to a concrete class and directly access it.
	        mBoundService = ((NativeService.NativeBinder)service).getService();
	        output("Connected");
	        mPeers.setService(mBoundService);
	        // Tell the user about this for our demo.
//	        Toast.makeText(Binding.this, R.string.native_service_connected,
	//                Toast.LENGTH_SHORT).show();
	    }

	    public void onServiceDisconnected(ComponentName className) {
	        // This is called when the connection with the service has been
	        // unexpectedly disconnected -- that is, its process crashed.
	        // Because it is running in our same process, we should never
	        // see this happen.
	    	onServiceUnbound();
	    }
	};
	
	void doBindService() {
		// Establish a connection with the service.  We use an explicit
	    // class name because we want a specific service implementation that
	    // we know will be running in our own process (and thus won't be
	    // supporting component replacement by other applications).
		// FIXME handle start errors
		Log.i("ipsec-tools", "doBindService");
		startService(new Intent(IPsecToolsActivity.this, 
	            NativeService.class));
	    bindService(new Intent(IPsecToolsActivity.this, 
	            NativeService.class), mConnection, 0);
	    mIsBound = true;
	}
	
	void doUnbindService() {
	    if (mIsBound) {
			Log.i("ipsec-tools", "doUnBindService");
	        // Detach our existing connection.
	        unbindService(mConnection);
        	stopService(new Intent(IPsecToolsActivity.this, 
        			NativeService.class));
	        onServiceUnbound();
	        mIsBound = false;
	    } else
			Log.i("ipsec-tools", "not bound");
	}
	
    private void output(final String str) {
    	int duration = Toast.LENGTH_SHORT;

    	Toast toast = Toast.makeText(this, str, duration);
    	toast.show();
    }
}
