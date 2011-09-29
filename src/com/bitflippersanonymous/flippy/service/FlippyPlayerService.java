package com.bitflippersanonymous.flippy.service;
import java.io.IOException;
import java.util.ArrayList;

import com.bitflippersanonymous.flippy.R;
import com.bitflippersanonymous.flippy.activity.FlippyInfoActivity;
import com.bitflippersanonymous.flippy.db.FlippyDatabaseAdapter;
import com.bitflippersanonymous.flippy.domain.*;
import com.bitflippersanonymous.flippy.domain.PlsEntry.Tags;
import com.bitflippersanonymous.flippy.util.*;


import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.XmlResourceParser;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;



public class FlippyPlayerService extends Service implements MediaPlayer.OnPreparedListener, 
	MediaPlayer.OnErrorListener, MediaPlayer.OnCompletionListener {
	public static final String ACTION_PLAY = Util.PACKAGE + ".action.PLAY";
	private MediaPlayer mMediaPlayer = null;
	private final IBinder mBinder = new LocalBinder();
	private PlsAdapter mAdapter = null;
	private int mCurPlayingPos = 0;
	private boolean mLoadComplete = false;
	final private ArrayList<Messenger> mClients = new ArrayList<Messenger>();
	private FlippyDatabaseAdapter mDbAdapter = null;


	public enum MediaState {
		STOP, PREPARE, PLAY
	}
	
	private MediaState mState = MediaState.STOP;
	
	public boolean getloadComplete() {
		return mLoadComplete;
	}
	
	public PlsAdapter getPlsAdapter() {
		return mAdapter;
	}

	public int getPosition() {
		return mCurPlayingPos;
	}
	
	public MediaState getState() {
		return mState;
	}

	public class LocalBinder extends Binder {
		public FlippyPlayerService getService() {
			return FlippyPlayerService.this;
		}
	}

	@SuppressWarnings("unchecked")
	public void onCreate() {
		final ArrayList<PlsEntry> entries = new ArrayList<PlsEntry>();
		mAdapter = new PlsAdapter(entries);
		final LoadTask loadTask = new LoadTask();
		loadTask.execute(entries);
	}

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }
	
	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	public void addClient(Messenger messenger) {
	if ( messenger != null )
		mClients.add(messenger);
	}
		
	public void removeClient(Messenger messenger) {
		mClients.remove(messenger);
	}
    
	@Override
	public void onPrepared(MediaPlayer mp) {
		mp.start();
		mState = MediaState.PLAY;
		sendUpdate();
	}

	@Override
	public void onDestroy() {
		if ( mMediaPlayer != null ) {
			mMediaPlayer.release();
			mMediaPlayer = null;
		}
		mState = MediaState.STOP;
		
		if ( mDbAdapter != null ) {
			mDbAdapter.close();
			mDbAdapter = null;
		}
		Log.w(getClass().getSimpleName(), "Destroyed");

	}

	public boolean startPlay(int position, int offset) {
		if ( mMediaPlayer == null ) {
			mMediaPlayer = new MediaPlayer();
			mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
			mMediaPlayer.setOnPreparedListener(this);
		}
		
		final int newPos = position + offset;
		if ( newPos < 0 || newPos >= getPlsAdapter().getCount() )
			position = 0;
		else
			position = position + offset;
		
		PlsEntry entry = mAdapter.getItem(mCurPlayingPos = position);
		mMediaPlayer.reset();
		try {
			mMediaPlayer.setDataSource(entry.get(Tags.enclosure));
		} catch (IllegalArgumentException e) {
			Log.w(getClass().getName(), "Exception setting data source", e);
			return false;
		} catch (IllegalStateException e) {
			Log.w(getClass().getName(), "Exception setting data source", e);
			return false;
		} catch (IOException e) {
			Log.w(getClass().getName(), "Exception setting data source", e);
			return false;
		}

		mMediaPlayer.prepareAsync();
		mState = MediaState.PREPARE;

		Intent intent = new Intent(getApplicationContext(), FlippyInfoActivity.class);
		PendingIntent pi = PendingIntent.getActivity(getApplicationContext(), 0,
				intent, PendingIntent.FLAG_UPDATE_CURRENT);
		Notification notification = new Notification();
		notification.tickerText = entry.get(Tags.title);
		notification.icon = R.drawable.icon;
		notification.flags |= Notification.FLAG_ONGOING_EVENT;
		notification.setLatestEventInfo(getApplicationContext(), "Flippy Player",
				"Playing: " + entry.get(Tags.title), pi);
		startForeground(R.string.radio_service_notif_id, notification);
		startService(new Intent(this, this.getClass()));
		sendUpdate();
		return true;
	}

	public void stopPlay() {
		stopForeground(true);
		stopSelf(); // Now OK to stop when all binders go away.
		onDestroy();
		sendUpdate();
		Log.i(getClass().getName(), "Stop Play");
	}

	public void sendUpdate() {
		try {
			for ( Messenger messenger : mClients ) {
				Message msg = Message.obtain();
				messenger.send(msg);
			}
		}
		catch (android.os.RemoteException e) {
			Log.w(getClass().getName(), "Exception sending message", e);
		}
	}

	// TODO: Need to call some sort of finish when this is done to make the task thread go away
	class LoadTask extends AsyncTask<ArrayList<PlsEntry>, Integer, Integer> {
		@Override
		protected Integer doInBackground(ArrayList<PlsEntry>... params) {
			ArrayList<PlsEntry> entries = params[0];
			XmlResourceParser parser = getResources().getXml(R.xml.accf_recent_message);
			try { 
				PodcastParser.parse(entries, parser); 
			} catch(Exception e) { 
				Log.e(getClass().getName(), "Exception parsing entries", e);
				return -1;
			}
			try { 
				populateDatabase(entries);
			} catch(Exception e) { 
				Log.e(getClass().getName(), "Exception populating database", e);
				return -1;
			}

			return 0;
		}

		@Override
		protected void onCancelled() {
			// Should we close db here?
		}

		@Override
		protected void onProgressUpdate(Integer... progress) {
		}

		@Override
		protected void onPostExecute(Integer result) {
			Log.i(getClass().getSimpleName(), "Load Complete");
			mLoadComplete  = true;
			sendUpdate();
		}

		@Override
		protected void onPreExecute() {
			mLoadComplete = false;
		}


	}

	@Override
	public boolean onError(MediaPlayer mp, int what, int extra) {
		onDestroy();
		return false;
	}

	@Override
	public void onCompletion(MediaPlayer mp) {
		onDestroy();		
	}
	
	// This happens in another thread LoadTask
	public void populateDatabase(ArrayList<PlsEntry> entries) {
		mDbAdapter = new FlippyDatabaseAdapter(this);
		mDbAdapter.recreate();
				
		for ( PlsEntry entry : entries )
			mDbAdapter.insertEntry(entry);

	}

}