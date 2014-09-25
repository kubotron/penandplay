//TODO on surface changed update display size 

package com.penandplay;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.media.MediaScannerConnection;
import android.media.MediaScannerConnection.MediaScannerConnectionClient;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.RotateAnimation;
import android.widget.ImageButton;
import android.widget.ImageView;

//TODO concurrent modification exception on back pressed
//TODO upload
//TODO stale preview pic?
//black video on return from sharing

public class DisplaySequenceActivity extends Activity implements SurfaceHolder.Callback, OnTouchListener, OnPreparedListener, OnCompletionListener{
	private static final String TAG = "DisplaySequence";
	public static String session = Environment.getExternalStorageDirectory().getPath() + File.separator + "Pen_and_play" + File.separator + "session" + File.separator;

	private static Uri videouri;
	private int timer =0;
	private int canvas_h;
	private int canvas_w;
	private Object timerlock=new Object();
	private Object pauselock=new Object();
	private Object uploadlock=new Object();
	private  int current_frame =0;

	public static double fps = 12;
	//public double interval;
	private int hide_time = 3; //seconds to hide controls
	private int timerLimit=(int)Math.round(hide_time*fps);
	private int new_hide_time = 7;

	//private static ScreenUpdater upd;
	
	private File[] ordered_files;
	int width;
	int height;
	//public String fileName;
	public float framerate;


	private HiderThread hiderThread;
	private TimerThread timerThread;

	private Thread frametimer;
	private Thread hider;

	private ImageButton playButton;
	private ImageButton photoButton;
	private ImageButton uploadButton;
	
	private ImageButton rightButton;
	private ImageButton leftButton;		
	private OnClickListener playListener;
	private OnClickListener uploadListener;
	private OnClickListener photoListener;
	private OnClickListener leftListener;
	private OnClickListener rightListener;
	
	//private OnClickListener settingsListener;
	private boolean paused=false;
	private boolean hidden=false;
	private boolean first=true;

	//private static boolean looping = true;
	//private static boolean video_ready=false;
	private String videofile;
	private boolean next_layer=true;
	//private boolean no_new_images=false;
	private TreeMap<Integer,ArrayList<String>> allgroups; 
	private BitmapManager  btmpass;
	private  String batchnr;
	private static boolean video_playing = false;
	private MediaPlayer mMediaPlayer;
	//private MediaController mcontroller;
	private BitmapFactory.Options options;

	private Animation hanim;
	private Animation uhanim;
	private Animation hctrl;
	private Animation uhctrl;
	private RotateAnimation ranim;
	//private LinearLayout arrows;

	private final Handler handler = new Handler();
	private ExecutorService executor; 

	SurfaceView sSurfaceView;
	SurfaceHolder sHolder;
	private Canvas canvas;	
	private Paint paint;
	public boolean running = false;


	private ImageView preview_pic; 
	private Bitmap preview_bitmap;


	public DisplaySequenceActivity() {
	}

	/** Called when the activity is first created. */
	@SuppressLint("NewApi")
	@Override
	public void onCreate(Bundle savedInstanceState) {	
		super.onCreate(savedInstanceState);

		paint= new Paint(Paint.FILTER_BITMAP_FLAG |
				Paint.DITHER_FLAG |
				Paint.ANTI_ALIAS_FLAG);
		btmpass = BitmapManager.getInstance();
		hiderThread = new HiderThread();
		hider=new Thread(hiderThread);
		timerThread = new TimerThread();
		frametimer=new Thread(timerThread);
		executor = Executors.newFixedThreadPool(1);
		setContentView(R.layout.sequence_view);
	
		//arrows = (LinearLayout) findViewById(R.id.arrow_controls);
		preview_pic=(ImageView)findViewById(R.id.video_preview_picture);
		//preview_pic.setVisibility(View.VISIBLE);
		//preview_pic.setBackgroundColor(Color.parseColor("#FF000000"));
		//preview_pic.setImageBitmap(null);			
		
		
		
		//sSurfaceView = new SurfaceView(this);
		//FrameLayout preview=(FrameLayout)findViewById(R.id.sequence);
		////Log.d(TAG, "frame layout set " + this.getClass());
		//preview.addView(sSurfaceView);
		sSurfaceView = (SurfaceView)findViewById(R.id.sequence);
		//sSurfaceView.setDrawingCacheEnabled(true);
		sHolder = sSurfaceView.getHolder();
		sHolder.addCallback(this);
		sSurfaceView.setOnTouchListener(this); 
    	sHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

  
		playButton=(ImageButton)findViewById(R.id.play_button);
		photoButton=(ImageButton)findViewById(R.id.photo_button);
		uploadButton=(ImageButton)findViewById(R.id.upload_button);
		//loopButton=(ImageButton)findViewById(R.id.loop_button);
		leftButton=(ImageButton)findViewById(R.id.left_button);
		rightButton=(ImageButton)findViewById(R.id.right_button);
		hanim = AnimationUtils.loadAnimation(getBaseContext(), R.anim.hidecontrols);
		uhanim = AnimationUtils.loadAnimation(getBaseContext(), R.anim.unhidecontrols);
		hctrl = AnimationUtils.loadAnimation(getBaseContext(), R.anim.hidearrows);
		uhctrl = AnimationUtils.loadAnimation(getBaseContext(), R.anim.unhidearrows);
		ranim = (RotateAnimation)AnimationUtils.loadAnimation(getBaseContext(), R.anim.rotate);
		/*		ranim = new RotateAnimation(0, 360,Animation.RELATIVE_TO_SELF,0.5f,Animation.RELATIVE_TO_SELF,0.5f);
		ranim.setStartOffset(0);
		ranim.setDuration(1000);
		ranim.setFillAfter(true);
	    ranim.setRepeatMode(Animation.INFINITE);*/
		hanim.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
              	//Log.d(TAG, "buttons hidden, removing listeners" );
        		//loopButton.setOnClickListener(null);
        		uploadButton.setOnClickListener(null);
        		photoButton.setOnClickListener(null); 
        		playButton.setOnClickListener(null);
        		//leftButton.setOnClickListener(null); 
        		//rightButton.setOnClickListener(null);
            }
            @Override
            public void onAnimationEnd(Animation animation) {
          
        		
            }
            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        });
		leftListener = new OnClickListener(){
			public void onClick(View v)			{ 
				//synchronized(timerlock){timer=0;}	
				handler.post(show_prev);
			}
		};
		rightListener = new OnClickListener(){
			public void onClick(View v)			{ 
				//synchronized(timerlock){timer=0;}	
				handler.post(show_next);
			}
		};

		playListener = new OnClickListener(){
			public void onClick(View v)
			{   if (paused){//TODO play pause 
				paused=false;
				synchronized(timerlock){timer=0;}
				handler.post(hide_orig);
				mMediaPlayer.seekTo((int)(current_frame*(1/fps)*1000));
				mMediaPlayer.start();
				//Log.d(TAG, "playing ");
				playButton.setImageResource(R.drawable.ic_pause);
				leftButton.setOnClickListener(null); 
        		rightButton.setOnClickListener(null);
        		leftButton.startAnimation(hctrl);
        		rightButton.startAnimation(hctrl);       		

				synchronized(pauselock){					
					pauselock.notifyAll();
					////Log.d(TAG, "notified ");
				}
				timerLimit=(int)Math.round(hide_time*fps);
				first=true;
					
			}else{
				mMediaPlayer.pause();
				paused=true;
				//video_playing=false;
				handler.post(show_orig);
				////Log.d(TAG, "button paused ");
				playButton.setImageResource(R.drawable.ic_play);
				leftButton.startAnimation(uhctrl);
        		rightButton.startAnimation(uhctrl);
        		leftButton.setOnClickListener(leftListener); 
        		rightButton.setOnClickListener(rightListener);
			}

			}
		};

		uploadListener = new OnClickListener(){
			public void onClick(View v)			{
				
				   final String filePathThis = session+"temp.webm";
				   //final Uri videouri;

				   MediaScannerConnectionClient mediaScannerClient = new
				   MediaScannerConnectionClient() {
				    private MediaScannerConnection msc = null;
				    {
				        msc = new MediaScannerConnection(getApplicationContext(), this);
				        msc.connect();
				    }

				    public void onMediaScannerConnected(){
				        msc.scanFile(filePathThis, null);
				    }


				    public void onScanCompleted(String path, Uri uri) {
				        //This is where you get your content uri
				            //Log.d(TAG, "URI is " + uri.toString());
				            videouri=uri;
				            msc.disconnect();
				    }
				   };
				
				 
				Intent shareIntent = new Intent();
				shareIntent.setAction(Intent.ACTION_SEND);
				//shareIntent.putExtra(android.content.Intent.EXTRA_STREAM,videouri);
				shareIntent.setDataAndType(videouri,"video/webm");				
				//shareIntent.putExtra(android.content.Intent.EXTRA_SUBJECT,"Pen&Play Video");
				
				startActivity(Intent.createChooser(shareIntent,"share:")); 

				// ContentValues content = new ContentValues(4);
				// content.put(Video.VideoColumns.DATE_ADDED,
				// System.currentTimeMillis() / 1000);
				// content.put(Video.Media.MIME_TYPE, "video/mp4");
				// content.put(MediaStore.Video.Media.DATA, "video_path");
				// ContentResolver resolver = getBaseContext().getContentResolver();
				// Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, content);

				 
		
				
				
				//ranim.setRepeatMode(Animation.INFINITE);
				//uploadButton.clearAnimation();
				
				//synchronized(timerlock){timer=0;}	
				//uploadButton.setImageResource(R.drawable.ic_running);
				//uploadButton.startAnimation(ranim);
				//uploadButton.setOnClickListener(null);
			}
		};
		
	
		
		photoListener = new OnClickListener(){
			public void onClick(View v) 
			{      		photoButton.setOnClickListener(null);
			Intent capture_intent = new Intent(getBaseContext(), PhotoActivity.class);
			if(next_layer){
				capture_intent.putExtra("nextlayer","CUTOUT");
			}else{
				capture_intent.putExtra("nextlayer","OPAQUE");
			}
			capture_intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
			setResult(RESULT_OK,capture_intent);			
			finish();
			}
		};
		leftButton.setVisibility(View.INVISIBLE);
		rightButton.setVisibility(View.INVISIBLE);
		Intent intent = getIntent(); // gets the previously created intent
		try{
			batchnr = intent.getStringExtra("this_batch");
		}catch(NullPointerException e){
			//Log.d(TAG, "no images, just replaying ");
			//no_new_images = true;
		}
		//no_new_images = false;
		//Log.d(TAG, "batch of images: " + batchnr);

		photoButton.setOnClickListener(photoListener); //TODO 
		playButton.setOnClickListener(playListener); 
		uploadButton.setOnClickListener(uploadListener);
		//playButton.setAnimation(ranim);
		video_playing=false;		
		
		allgroups = btmpass.getAllGroups();		    
		options = new BitmapFactory.Options();
		options.inPreferredConfig = Bitmap.Config.ARGB_8888;
		
		//preview_pic.setImageBitmap(getFrame(0));
		//Log.d(TAG, "first preview image!!!!!!!! ");
		allgroups = btmpass.getAllGroups();	
		
		BitmapManager.clearCache();

	}

	@SuppressLint("NewApi")

	@Override
	public void onStart()
	{   super.onStart();
	//Log.d(TAG, "onStart Called" );	    


	}

	@Override
	public void onBackPressed() {	    		
		super.onBackPressed();
		Integer last_group = null;
		for (Integer key : allgroups.keySet()){
			last_group=key;  	
		}
		btmpass.removeGroup(last_group);
		Intent capture_intent = new Intent(getBaseContext(), PhotoActivity.class);
		capture_intent.putExtra("nextlayer","QUIT");
		setResult(RESULT_OK,capture_intent);
		finish();
	}

	@Override
	public void onResume()
	{   super.onResume();
	//Log.d(TAG, "onResume Called" );
	//paused=false;// pause media player too
	//start media player?
	if(paused){unhide_controls();}
	//paused=false;
	//running=true;
		
	}
	
	@Override
	public void onPause()
	{	super.onPause();
		//Log.d(TAG,"onPause event");
		paused=true;
		playButton.setImageResource(R.drawable.ic_play);
		mMediaPlayer.pause();
		//release mediaplayer?
	}

	@Override
	public void onDestroy() {	        
		super.onDestroy();
		//Log.d(TAG,"onDestroy event");
		if(mMediaPlayer!=null){
			mMediaPlayer.release();
			mMediaPlayer=null;
			running = false;
		}

	}



	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width,int height) {
		// TODO Auto-generated method stub			
	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		//Log.d(TAG,"Surface created");				
			mMediaPlayer = new MediaPlayer();	
			mMediaPlayer.setOnPreparedListener(this);
			mMediaPlayer.setOnCompletionListener(this);
			///if(!video_playing){
			//	video_playing=true;
				startVideo();
			//}
			handler.post(activate_ctrls);
		
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		//Log.d(TAG,"Surface destroyed");
	}					

	

	private void startVideo(){
		try {					
			mMediaPlayer.setDataSource(session+"temp.webm");
			//Log.d(TAG,"video data source set:" +session+"temp.webm");
		} catch (IllegalArgumentException e) {
			//Log.d(TAG,"mplayer exception: " + e);
			//video_playing=false;
		} catch (SecurityException e) {
			//Log.d(TAG,"mplayer exception: " + e);
			//video_playing=false;
		} catch (IllegalStateException e) {
			//Log.d(TAG,"mplayer exception: " + e);
			//video_playing=false;
		} catch (IOException e) {
			//Log.d(TAG,"mplayer exception: " + e);
			//video_playing=false;
		}

		mMediaPlayer.setDisplay(sHolder);						            	
		//Log.d(TAG,"display set");
		mMediaPlayer.setLooping(true);
		//Log.d(TAG,"looping set");

		try {
			//Log.d(TAG,"preparing_async");
			mMediaPlayer.prepareAsync();
		} catch (IllegalStateException e) {
			//Log.d(TAG,"mplayer exception: " + e);
			//video_playing=false;
		} //catch (IOException e) {
		//	//Log.d(TAG,"mplayer exception: " + e);
		//	video_playing=false;
		//}
		//if(video_playing){
		//	video_ready=true;
		//	//Log.d(TAG,"starting");
		//	mMediaPlayer.start();
		//	//Log.d(TAG,"MEDIA PLAYER STARTED");
		//}
	}	


	final Runnable hide_ctrls = new Runnable() {
		public void run() {
			hide_controls();
		}
	};

	final Runnable unhide_ctrls = new Runnable() {
		public void run() {
			unhide_controls();
		}
	};

	final Runnable activate_ctrls = new Runnable() {	
		public void run() {
			activate_controls();
		}
	};

	final Runnable show_orig = new Runnable() {	
		public void run() {
			show_original();
		}
	};


	final Runnable hide_orig = new Runnable() {	
		public void run() {
			hide_original();
		}
	};
	
	final Runnable show_next = new Runnable() {	
		public void run() {
			if(current_frame>=timerThread.limit-1){
				current_frame= 0;
			}else{
				current_frame= current_frame+1;
			}
			
			//Log.d(TAG, "current frame" + current_frame);	
			Bitmap bitmap = BitmapManager.getBitmapFromMemCache(current_frame);											
			if (bitmap == null) {
				//Log.d(TAG, "CACHE MISS");
				bitmap=getFrame(current_frame);	
				preview_pic.setImageBitmap(bitmap);	
				BitmapManager.addBitmapToMemoryCache(current_frame, bitmap);
			}else{//Log.d(TAG, "CACHE HIT");	
			preview_pic.setImageBitmap(bitmap);	
			}				
			//preview_pic.setVisibility(View.VISIBLE);				
			
		}
	};
	
	final Runnable show_prev = new Runnable() {	
		public void run() {
			if(current_frame<=0){
				current_frame= timerThread.limit-1;
			}else{
				current_frame= current_frame-1;
			}
			
			//Log.d(TAG, "current frame" + current_frame);
			Bitmap bitmap = BitmapManager.getBitmapFromMemCache(current_frame);											
			if (bitmap == null) {
				//Log.d(TAG, "CACHE MISS");
				bitmap=getFrame(current_frame);	
				preview_pic.setImageBitmap(bitmap);	
				BitmapManager.addBitmapToMemoryCache(current_frame, bitmap);
			}else{//Log.d(TAG, "CACHE HIT");
			preview_pic.setImageBitmap(bitmap);	
			}	
			//preview_pic.setVisibility(View.VISIBLE);
		}
	};


	private void hide_original(){
		preview_pic.setImageBitmap(null);			
		preview_pic.setVisibility(View.GONE);
	}	

	private void show_original(){
		preview_pic.setImageBitmap(getFrame(timerThread.frame));			
		preview_pic.setVisibility(View.VISIBLE);
	}	
	
	

	private void activate_controls(){
		playButton.clearAnimation();
		playButton.setOnClickListener(playListener);
		playButton.setImageResource(R.drawable.ic_pause);
		uploadButton.setOnClickListener(uploadListener);
	}		


	private void hide_controls(){
		//Log.d(TAG, "hiding buttons" );
		//leftButton.setVisibility(View.GONE);
		//rightButton.setVisibility(View.GONE);
		photoButton.startAnimation(hanim); 
		playButton.startAnimation(hanim); 
		uploadButton.startAnimation(hanim);
		//loopButton.startAnimation(hanim);
		//rightButton.startAnimation(hanim);
		//leftButton.startAnimation(hanim);
		
	}


	private void unhide_controls(){
		//Log.d(TAG, "unhiding buttons" );
		uploadButton.startAnimation(uhanim);
		photoButton.startAnimation(uhanim); 
		playButton.startAnimation(uhanim); 
		//loopButton.startAnimation(uhanim); 
		
		if(paused){
			rightButton.startAnimation(uhanim);
			leftButton.startAnimation(uhanim);	
			//leftButton.setVisibility(View.VISIBLE);
			//rightButton.setVisibility(View.VISIBLE);
		}
		//loopButton.setOnClickListener(uploadListener);
		uploadButton.setOnClickListener(uploadListener);
		photoButton.setOnClickListener(photoListener); 
		playButton.setOnClickListener(playListener);
		//loopButton.setOnClickListener(loopListener);
		//leftButton.setOnClickListener(leftListener);
		//rightButton.setOnClickListener(rightListener);
		
	}


	@Override
	public boolean onTouch(View arg0, MotionEvent event) {	    
		timerLimit=(int)Math.round(new_hide_time*fps);    		
		synchronized(timerlock){timer=0; }			
		//Log.d(TAG, "reset timer ");
		return false;
	}

	public void onPrepared(MediaPlayer player) {
		//Log.d(TAG,"prepared!!!!!!!!!");
		player.start();
		running = true;
		if(!video_playing){
			video_playing=true;
			hider.start();
			frametimer.start();
		}else{
			hiderThread.ii=0;
			timerThread.frame=0;
		}
	}

	private Bitmap getFrame(int i){
		Display display = getWindowManager().getDefaultDisplay();
		Point size = getSize(display);
		width = size.x;
		height = size.y;		

		int w=0;
		int h=0;
		Canvas frame = null;
		Bitmap cs = null;
		Bitmap bitmap = null;
		cs = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); 
		frame = new Canvas(cs);

		for (Integer key : allgroups.keySet()){
			//Log.d(TAG, " displaying group " + key + " image " + allgroups.get(key).get(i));

				bitmap=BitmapFactory.decodeFile(session+allgroups.get(key).get(i), options);			        
	
			w=bitmap.getWidth();
			h=bitmap.getHeight();
			//Log.d(TAG, "w h " +w+"x" +h );		

			frame.drawBitmap(bitmap, (width-w)/2, (height-h)/2,  null); 					
		}
		return cs;	
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) 
	{
		super.onConfigurationChanged(newConfig);
	}

	private class HiderThread implements Runnable {
		double interval = 1/fps;
		public int ii =0;
		int limit=btmpass.longestLayer;//TODO get frames from media player
		int inter= (int) Math.round(interval*1000);
		public void run(){
			//Log.d(TAG, "time thread starting");
			while (running){							
				if(!paused){
					synchronized(timerlock){
						if(timer==0 && !first && hidden){
							handler.post(unhide_ctrls);
							hidden=false;
						}
						timer++;	
						if((timer>timerLimit)&&!hidden){
							//Log.d(TAG, "hiding buttons");
							handler.post(hide_ctrls);
							hidden=true;
							first=false;
						}
					}		
					try {	 		
						Thread.sleep(inter);
						//Thread.sleep(waittime*10);
					} catch (InterruptedException e) {
						return;
					}
				}else{		//					
					try {
						synchronized(pauselock){	
							//Log.d(TAG, "hider paused");
							pauselock.wait();
							//Log.d(TAG, "hider unpaused");
							
						}							
					} catch (InterruptedException e1) {
						return;
					}		
				}								
				ii++;
				if(ii>=limit){						        
					ii=0;
				}						        
			}			   
		}
	}

	private class TimerThread implements Runnable {
		double interval = 1/fps;
		public int frame =0;
		public int limit=BitmapManager.longestLayer;
		int inter= (int) Math.round(interval*1000);
		public void run(){
			//Log.d(TAG, "time thread starting");
			while (running){							
				if(!paused){											 
					try {	 		
						Thread.sleep(inter);
					} catch (InterruptedException e) {
						return;
					}									 
				}else{		//					
					try {
						synchronized(pauselock){	
							//Log.d(TAG, "timer paused on frame " + frame);
							pauselock.wait();
							//Log.d(TAG, "timer unpaused");
							frame=current_frame;
						}							
					} catch (InterruptedException e1) {
						return;
					}
				}								
				frame++;				
				if(frame>=limit){						        
					frame=0;
				}
				current_frame=frame;
				//Log.d(TAG, "timer frame " + frame);
			}			   
		}
	}

	Point getSize(Display display) 	{
		Point outSize = new Point();
		boolean sizeFound = false;

		try 
		{
			// Test if the new getSize() method is available
			Method newGetSize = 
					Display.class.getMethod("getSize", new Class[] { Point.class });

			// No exception, so the new method is available

			newGetSize.invoke(display, outSize);
			sizeFound = true;
			//Log.d(TAG, "Screen size is " + outSize.x + " x " + outSize.y);
		} 
		catch (NoSuchMethodException ex) 
		{
			// This is the failure I expect when the deprecated APIs are not available
			//Log.d(TAG, "getSize not available - NoSuchMethodException");
		}  
		catch (InvocationTargetException e) 
		{
			//Log.d(TAG, "getSize not available - InvocationTargetException");
		} 
		catch (IllegalArgumentException e) 
		{
			//Log.d(TAG, "getSize not available - IllegalArgumentException");
		} 
		catch (IllegalAccessException e) 
		{
			//Log.d(TAG, "getSize not available - IllegalAccessException");
		}

		if (!sizeFound)
		{
			//Log.d(TAG, "Used deprecated methods as getSize not available");
			outSize = new Point(display.getWidth(), display.getHeight());
		}

		return outSize;
	}



	@Override
	public void onCompletion(MediaPlayer mp) {	       
		////Log.d(TAG, "COMPLETE");			
	}
/*
	private long getVideoIdFromFilePath(String filePath,
	        ContentResolver contentResolver) {


	    long videoId;
	    //Log.d(TAG,"Loading file " + filePath);

	            // This returns us content://media/external/videos/media (or something like that)
	            // I pass in "external" because that's the MediaStore's name for the external
	            // storage on my device (the other possibility is "internal")
	    Uri videosUri = MediaStore.Video.Media.getContentUri("external");

	    //Log.d(TAG,"videosUri = " + videosUri.toString());

	    String[] projection = {MediaStore.Video.VideoColumns._ID};

	    // TODO This will break if we have no matching item in the MediaStore.
	    Cursor cursor = contentResolver.query(videosUri, projection, MediaStore.Video.VideoColumns.DATA + " LIKE ?", new String[] { filePath }, null);
	    cursor.moveToFirst();

	    int columnIndex = cursor.getColumnIndex(projection[0]);
	    videoId = cursor.getLong(columnIndex);

	    //Log.d(TAG,"Video ID is " + videoId);
	    cursor.close();
	    return videoId;
	}*/

}