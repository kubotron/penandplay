package com.penandplay;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.InvalidPropertiesFormatException;
import java.util.LinkedList;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

import com.penandplay.DisplaySequenceActivity;
import com.penandplay.PhotoView;
import com.penandplay.BitmapManager;
import com.penandplay.R;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.hardware.Camera;
import android.hardware.Camera.AutoFocusCallback;
import android.hardware.Camera.PictureCallback;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.View.OnTouchListener;
import android.view.animation.AnimationUtils;
import android.view.animation.RotateAnimation;
import android.widget.FrameLayout;
import android.widget.ImageButton;	
import android.widget.ImageView;


//TODO not always showing previews as they are being saved....
//TODO what if back pressed while encoding
//TODO play button unresponding sometimes after back pressed
//TODO play after coming back with no photos?
//TODO what if native returns without photos?
//TODO no controls on single image? no unhiding?
//TODO save fps and loop preferences
//TODO test differnet fps on dif. layers
//TODO late press of play button (on encoding?) doesnt turn the wheel


public class PhotoActivity extends Activity implements OnTouchListener, PictureCallback {
	public static String session = Environment.getExternalStorageDirectory().getPath() + File.separator + "Pen_and_play" + File.separator + "session" + File.separator;
	private static final String TAG = "Photo Activity";
	public native void FindFrames(String s, String t, String u, boolean color, boolean cutBG, int width, int height, int target_width, int target_height,  boolean columns, boolean framenrs, boolean use_single_page);
	private Camera mCamera;
	private PhotoView mPreview;
	private BitmapManager btmpass;
	private String this_batch;
	private ArrayList<Integer> this_batch_nrs;
	private static Semaphore available;
	private Semaphore queueSemaphore;
	private static Semaphore waitSemaphore;
	private Semaphore assetSemaphore;
	public static boolean keep_going = true;//JNI signal for native code!
	private BitmapFactory.Options options;
	private TreeMap<Integer,ArrayList<String>> allgroups; 

	//private SurfaceView mSurfaceView = null;
	//private SurfaceHolder mHolder;
	private boolean nextPhoto = false;
	private boolean camera_processing = false;
	private boolean global_frame_nrs = false;
	private boolean stop_first_preview = false;
	private boolean ok_to_finish = true;
	private boolean looping = true;
	public double fps = 12;
	
	private boolean play_waiting;
	private Bitmap previewBitmap;	
	private Bitmap video_bitmap;


	public String dirName;
	public String fileName;
	public String storage;
	private String session_str;

	private RotateAnimation ranim;
	private VideoEncoder encoder;
	private OnClickListener playListener;
	private OnClickListener photoListener;
	private OnClickListener colorListener;
	private OnClickListener modeListener;
	private OnClickListener flashListener;
	private OnClickListener loopListener;
	private OnClickListener fpsListener;
	private AutoFocusCallback takePicture;
	private ImageButton playButton;
	private  ImageButton photoButton;
	private  ImageButton colorButton;
	private  ImageButton modeButton;
	private  ImageButton flashButton;
	private  ImageButton loopButton;
	private  ImageButton fpsButton;
	private  ImageButton fpsValueButton;
	private boolean use_columns=true;
	private boolean use_color=false;
	private boolean use_framenrs=false;
	private boolean flash_on = false;
	private boolean use_single_page = false;
	private boolean got_assets= false;
	private File session_directory;
	private File old_session_directory;
	private int width;
	private int height;
	private int fpsMode = 2;
	private int activeProcessingThreads=0;
	private boolean next_layer = false;
	private  boolean ok_to_playback=true;
	private  boolean stop_encoding = false;
	private int total_shots;
	private int shots;
	private  Object lock;
	public Canvas canvas;	
	private Paint paint;
	private LinkedBlockingQueue<String> photoQueue; 
	private LinkedBlockingQueue<Bitmap> videoQueue;
	private ArrayList<String> photoQueueControl; 
	private Thread waitingForFirstPhoto;
	private Thread showingFirstPreview;
	private Thread waitingForAllPics;
	private Thread processingPhotos;
	public static int target_width;
	public static int target_height;
	private  ExecutorService executor; 
	private final Handler handler = new Handler();	
	public  int TIMEOUT;
	private Collection<Future<?>> futures;
	private  ImageView preview_pic;
	private  Encoding enc;
	private  Thread encoding ; 
	private  Future<String> future_video;
	private  int mode=0;
	private  Properties prop;
	private  String propLocation;


	static {
		System.loadLibrary("find_frames");
		//Log.d(TAG, "Native library loaded");
	}
	public PhotoActivity() {

	}
	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
	
		waitSemaphore = new Semaphore(-1, true);		
		available = new Semaphore(1, true);
		queueSemaphore = new Semaphore(-1, true);
		assetSemaphore = new Semaphore(-1, true);
		total_shots=0;
		shots = 0;
		keep_going=true;
		//TODO use point.size for api > 13 
		Display display = getWindowManager().getDefaultDisplay();    	
		Point size = getSize(display);
		if(size.x>size.y){
			width = size.x;
			height = size.y;
		}else{
			width = size.y;
			height = size.x;
		}

		paint= new Paint(Paint.FILTER_BITMAP_FLAG |
				Paint.DITHER_FLAG |
				Paint.ANTI_ALIAS_FLAG);

		this_batch= new String();
		this_batch_nrs = new ArrayList<Integer>();
		storage = Environment.getExternalStorageDirectory().getPath() + File.separator + "Pen_and_play" + File.separator;
		propLocation = storage+"conf.properties";

		btmpass = BitmapManager.getInstance();
		photoQueue = new LinkedBlockingQueue<String>();
		videoQueue = new LinkedBlockingQueue<Bitmap>();
		photoQueueControl = new ArrayList<String>(); 
		BitmapManager.clearAll();
		BitmapManager.width=width;
		BitmapManager.height=height;
		lock = new Object();

		options = new BitmapFactory.Options();
		options.inPreferredConfig = Bitmap.Config.ARGB_8888;

		prop = new Properties();
		//prop.load(context.getResources().openRawResource(R.raw.config));

		futures = new LinkedList<Future<?>>();        
		executor = Executors.newFixedThreadPool(1);

		//open xmls and save to sd card for native access 
		mPreview = new PhotoView(this);
		setContentView(R.layout.photo_surface_view);

		FrameLayout preview=(FrameLayout)findViewById(R.id.photo_surface_view);
		//Log.d(TAG, "frame layout set " + this.getClass());
		preview.addView(mPreview);
		mPreview.setOnTouchListener(this);
		preview_pic=(ImageView)findViewById(R.id.preview_picture);

		playButton=(ImageButton)findViewById(R.id.play_button);
		photoButton=(ImageButton)findViewById(R.id.photo_button);
		flashButton=(ImageButton)findViewById(R.id.flash_button);
		modeButton=(ImageButton)findViewById(R.id.mode_button);
		colorButton=(ImageButton)findViewById(R.id.color_button);
		loopButton=(ImageButton)findViewById(R.id.loop_button);
		fpsValueButton=(ImageButton)findViewById(R.id.fps_value_button);
		fpsButton=(ImageButton)findViewById(R.id.fps_button);


		ranim = (RotateAnimation)AnimationUtils.loadAnimation(getBaseContext(), R.anim.rotate);

		CopyAssets copy = new CopyAssets();
		Thread copying = new Thread(copy);
		copying.start();
		//Log.d(TAG, "started copying assets  ");		

		playListener = new OnClickListener(){
			public void onClick(View v)
			{  
				if(available.hasQueuedThreads()||available.availablePermits()==0){
					//Log.d(TAG, "will disable play");
					disablePlay();                  
				}       

				Waiter waiter = new Waiter();
				waitingForAllPics = new Thread(waiter);
				waitingForAllPics.start();
			}
		};    


		photoListener = new OnClickListener(){
			public void onClick(View v)
			{  
				playButton.setOnClickListener(null);
				photoButton.setOnClickListener(null);
				photoButton.setImageResource(R.drawable.ic_running);
				photoButton.setAnimation(ranim);
				Camera.Parameters params = mPreview.mCamera.getParameters();
				if(flash_on){									
					params.setFlashMode(Camera.Parameters.FLASH_MODE_ON);
				}
				else{					
					params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
				}
				mPreview.mCamera.setParameters(params);	
				mPreview.mCamera.autoFocus(takePicture);               
				//        mCamera.startPreview();
			}
		};

		flashListener = new OnClickListener(){
			public void onClick(View v)
			{  
				Camera.Parameters params = mPreview.mCamera.getParameters();
				if(flash_on){
					flash_on=false;
					flashButton.setImageResource(R.drawable.ic_no_flash);					
					params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
				}
				else{
					flash_on=true;
					flashButton.setImageResource(R.drawable.ic_flash_fill);
					params.setFlashMode(Camera.Parameters.FLASH_MODE_ON);
				}
				mPreview.mCamera.setParameters(params);			
			}
		};

		colorListener = new OnClickListener(){
			public void onClick(View v)
			{  				
				if(use_color){
					use_color=false;
					colorButton.setImageResource(R.drawable.ic_grayscale);		

				}
				else{
					use_color=true;
					colorButton.setImageResource(R.drawable.ic_color);					
				}
			}
		};

		modeListener = new OnClickListener(){
			public void onClick(View v)
			{  //Log.d(TAG,"photo click event ");	
			mode++;
			if(mode>3){
				mode=0;
				modeButton.setImageResource(R.drawable.ic_columns);		
				use_columns=true;					
				use_framenrs=false;
				use_single_page =false;
			}else if(mode==1){					
				modeButton.setImageResource(R.drawable.ic_rows);
				use_columns=false;					
				use_framenrs=false;
				use_single_page =false;
			}else if(mode==2){				
				modeButton.setImageResource(R.drawable.ic_numbers);
				use_columns=true;					
				use_framenrs=true;
				use_single_page =false;
			}else if(mode==3){					
				modeButton.setImageResource(R.drawable.ic_paper);
				use_columns=false;					
				use_framenrs=false;
				use_single_page =true;
			}
			}
		};

		fpsListener = new OnClickListener(){
			public void onClick(View v)
			{  //Log.d(TAG,"FPS WAS " + fps + ", fpsmode was " + fpsMode);	
			
			fpsMode++;
			if(fpsMode>3){//2fps
				fpsMode=0;
				fpsValueButton.setImageResource(R.drawable.ic_3fps);	
				setFps(3);
				
			}else if(fpsMode==1){//6fps
				fpsValueButton.setImageResource(R.drawable.ic_6fps);
				setFps(6);
				
			}else if(fpsMode==2){//12fps
				fpsValueButton.setImageResource(R.drawable.ic_12fps);
				setFps(12);
			
			}else if(fpsMode==3){//24fps				
				fpsValueButton.setImageResource(R.drawable.ic_24fps);
				setFps(24);
			}
			}
		};
		
		loopListener = new OnClickListener(){ 
			public void onClick(View v)			{ 
					
				if(looping){
					looping=false;					
					loopButton.setImageResource(R.drawable.ic_once);
					
				}else{
					looping=true;						
					loopButton.setImageResource(R.drawable.ic_loop);
				}
			}
		};


		photoButton.setOnClickListener(photoListener); 
		modeButton.setOnClickListener(modeListener); 
		flashButton.setOnClickListener(flashListener); 
		colorButton.setOnClickListener(colorListener); 
		loopButton.setOnClickListener(loopListener);
		fpsButton.setOnClickListener(fpsListener);
		fpsValueButton.setOnClickListener(fpsListener);


		photoButton.setOnLongClickListener(new OnLongClickListener()
		{		//TODO submenu
			public boolean onLongClick(View v)
			{           	
				return true;
			}
		}); 

		takePicture = new AutoFocusCallback(){
			
			@Override
			public void onAutoFocus(boolean arg0, Camera arg1) {
				playButton.setOnClickListener(null);
				photoButton.setOnClickListener(null);
				camera_processing = true;
				shoot(mPreview.mCamera);	  
				//Log.d(TAG,"mode " + mode);
				}
		};

	}

	private void disablePlay(){
	
		playButton.setImageResource(R.drawable.ic_running);
		playButton.startAnimation(ranim);
		photoButton.setOnClickListener(null);
		playButton.setOnClickListener(null);
		//Log.d(TAG,"play button disabled");

	}

	public  void enablePlay(){
		//Log.d(TAG,"enabling play button!!!!!!!!!!!!!!!!!!");
		
		playButton.setOnClickListener(playListener);
		photoButton.setOnClickListener(photoListener);
		playButton.clearAnimation();
		playButton.setImageResource(R.drawable.ic_play);

	}

	private class Waiter implements Runnable{
		public void run(){
			if(available.hasQueuedThreads()||available.availablePermits()==0){//if processing threads are running, wait on first barrier
				ok_to_finish=false;
				play_waiting = true;
				//Log.d(TAG,"play button waiting");
				synchronized(lock){
					try {
						lock.wait();
					} catch (InterruptedException e) {
						//Log.d(TAG,"play button interrupted, returning");
						return;
					}
				}
				//Log.d(TAG,"play button proceeding");
			}    	

			if(ok_to_playback){   
				Intent intent = new Intent(getBaseContext(), DisplaySequenceActivity.class);
				if(shots!=0){//if some shots were taken
					String batch=Integer.valueOf(total_shots).toString(); 
					intent.putExtra("this_batch",batch );
					
					Integer group = total_shots;
					if(looping){
						//Log.d(TAG,"setting to loop, batch " +batch +", group "+group +", total_shots "+total_shots);
						BitmapManager.loop_preferences.put(group, "LOOP");
					}else{
						//Log.d(TAG,"setting to play once, batch " +batch +", group "+group +", total_shots "+total_shots);
						BitmapManager.loop_preferences.put(group, "ONCE");
					}
					
					//Log.d(TAG,"finalizing group " +batch +", layers "+this_batch_nrs);
					
					
					if(!global_frame_nrs){
						btmpass.finalize(this_batch_nrs);
					}else{
						btmpass.reorderBitmaps(this_batch_nrs);
					}
					
					switch(fpsMode){			
					case 0: fps=3; DisplaySequenceActivity.fps=3; break;
					case 1: fps=6; DisplaySequenceActivity.fps=6; break;
					case 2: fps=12; DisplaySequenceActivity.fps=12; break;
					case 3: fps=24; DisplaySequenceActivity.fps=24; break;				
				
					}	
					//Log.d(TAG,"back from reordering");	
				}else{
					//Log.d(TAG,"NO PHOTOS TAKEN?");
				}
				stop_first_preview=true;
				waitingForFirstPhoto.interrupt();
				showingFirstPreview.interrupt();					
				handler.post(remove_camera);
				fpsButton.setOnClickListener(null);
				fpsValueButton.setOnClickListener(null);
				enc = new Encoding();
				future_video= executor.submit(enc);							

				try {//wait here until video encoded
					String videofile = (String)  future_video.get(); // use future
				} catch (InterruptedException e) {
					//Log.d(TAG,"video encoding interrupted, returning");
					return;
				} catch (ExecutionException e) {	
					//Log.d(TAG,"video encoding interrupted, returning");
					return;
				} finally{

				}			
				handler.post(remove_preview);	
				intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);    	
				startActivityForResult(intent,2);

			}else{return;}
		}
	}

	protected void onSaveInstanceState(Bundle state) {
		super.onSaveInstanceState(state);

	}

	@Override
	public void onBackPressed() {
		//super.onBackPressed();
		if(!ok_to_finish){
			
			ok_to_finish=true;
			stop_encoding=true;
			keep_going=false;	
			ok_to_playback=false;
			stop_first_preview=true;
			if(waitingForFirstPhoto!=null){
				waitingForFirstPhoto.interrupt();}
			if(showingFirstPreview!=null){
				showingFirstPreview.interrupt();}
			if(waitingForAllPics!=null){
				waitingForAllPics.interrupt();
				try {
					//Log.d(TAG, "waiting to end threads");	

					waitingForFirstPhoto.join();
					showingFirstPreview.join();
					waitingForAllPics.join();
					//String dummy = future_video.get();

					//Log.d(TAG, "all joined, ok to playback next round");
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}finally{
					//ok_to_finish=true;
				}
			}
			//Log.d(TAG,"collecting processing threads");  
			for (Future<?> future:futures) {//TODO wait until canceled	       
				future.cancel(true);
				
			} 
			resetActivity();           	
		}else{
			//Log.d(TAG, "finishing activity!");
			finish();
		}

	}

	@SuppressLint("NewApi")
	@Override
	public void onResume()
	{   super.onResume();
	mPreview.setVisibility(View.VISIBLE);
	}
	@Override
	public void onPause()
	{
		super.onPause();
	}
	public void onDestroy() {
		super.onDestroy();
		//Log.d(TAG,"destroy");
		BitmapManager.clearAll();
		SetProperties setProperties= new SetProperties();
		Thread t = new Thread(setProperties);
		t.start();
		
		
	}
	@Override
	public boolean onTouch(View v, MotionEvent event) {
		//Log.d(TAG,"ON TOUCH");
		return false;
	}
	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_CAMERA) {
			if(!camera_processing){  
				//Log.d(TAG,"Camera Key down ");
				Camera.Parameters params = mPreview.mCamera.getParameters();
				if(flash_on){									
					params.setFlashMode(Camera.Parameters.FLASH_MODE_ON);
				}
				else{					
					params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
				}
				mPreview.mCamera.setParameters(params);	
				mPreview.mCamera.autoFocus(takePicture);  
				return true; // consume event
			}
		}
		return super.onKeyDown(keyCode, event);
	}

	public void shoot(Camera c){
		c.takePicture(null,null,this);	
	}    		      


	@Override
	public void onPictureTaken(byte[] data, Camera camera) {    	
		try { //async task for storing the photo        			
			total_shots++;
			new SavePhotoTask().execute(data, null, null);
			//Log.d(TAG, "AsyncTask started");

		} catch (final Exception e){
			//Log.d(TAG, "Cannot save" , e);
			//some exceptionhandling
		}finally{
			nextPhoto = true;

		}
		camera.startPreview(); 		
		camera_processing = false;
	}



	class SavePhotoTask extends AsyncTask<byte[], Void, Void> {
		@SuppressLint("SimpleDateFormat")

		private String dirName;
		private String fileName;
		private Integer total;


		@Override
		protected void onPreExecute(){      
			shots++;

			total = new Integer(total_shots);
			dirName =storage +"session"+File.separator + total_shots+ File.separator;        

			File directory = new File(dirName);
			directory.mkdirs();
			File orig_directory = new File(dirName+File.separator+"orig"+File.separator);
			orig_directory.mkdirs();
			fileName =dirName  +"source.JPG";    
		}


		@Override
		protected Void doInBackground(byte[]... jpeg) {

			try {
				FileOutputStream fos=new FileOutputStream(fileName);
				fos.write(jpeg[0]);
				fos.close();
			}
			catch (java.io.IOException e) {
				Log.e(TAG,"Exception in SavePhotoTask");
			}


			return(null);
		}


		@Override
		protected void onPostExecute(Void result){  	
			ProcessPhotos proc = new ProcessPhotos(total,dirName,fileName,shots);  
			Thread t = new Thread(proc);
			futures.add(executor.submit(t));
			activeProcessingThreads++;

			photoButton.setOnClickListener(photoListener);
			photoButton.setImageResource(R.drawable.ic_photo);
			photoButton.clearAnimation();

			playButton.setImageResource(R.drawable.ic_play);
			playButton.setOnClickListener(playListener);

		}
	}

	private void stopPreviewAndFreeCamera() {
		if (mPreview.mCamera != null) {
			mPreview.mCamera.stopPreview();
			mPreview.mCamera.release();
			mPreview.mCamera = null;
		}
	}

	private class CopyAssets implements Runnable {
		public void run(){
			//create the directory structure and move old session 	
			File directory = new File(Environment.getExternalStorageDirectory()+File.separator+"Pen_and_play");
			directory.mkdirs();        
			session_directory = new File(Environment.getExternalStorageDirectory()+File.separator+"Pen_and_play"+File.separator+"session"+File.separator);
			old_session_directory = new File(Environment.getExternalStorageDirectory()+File.separator+"Pen_and_play"+File.separator+"session.old"+File.separator);
			deleteDirectory(old_session_directory);
			session_directory.renameTo(old_session_directory);	
			session_directory.mkdirs();        
			session_str=session_directory.toString();
			
			AssetManager assetManager = getAssets();
			String[] files = null;
			try {
				files = assetManager.list("");
			} catch (IOException e) {
				Log.e(TAG, "Failed to get asset file list.", e);
			}
			for(String filename : files) {
				InputStream in = null;
				OutputStream out = null;
				try {     
					in = assetManager.open(filename);
					File outFile = new File(storage,filename);
					out = new FileOutputStream(outFile);
					copyFile(in, out);
					in.close();
					in = null;
					out.flush();
					out.close();
					out = null;
					//Log.d(TAG, "copying "+ filename + " to  " + storage);
				} catch(IOException e) {
					// Log.d(TAG, "Failed to copy asset file: " + filename, e);
				}                   
			}
			//Log.d(TAG, "releasing assetsemaphore");

			InputStream inp = null;
			File propert = new File(propLocation);
			boolean no_properties=false;
			try {
				inp = new FileInputStream(propert);
			} catch (FileNotFoundException e) {
				no_properties=true;
			}			
			if(!no_properties){
				try {
					prop.loadFromXML(inp);
				} catch (InvalidPropertiesFormatException e) {
					no_properties=true;
				} catch (IOException e) {
					no_properties=true;
				}

				String smode = prop.getProperty("MODE");
				String scolor = prop.getProperty("COLOR");
				String sflash = prop.getProperty("FLASH");
				String sfps = prop.getProperty("FPSMODE");//TODO
				String sloop = prop.getProperty("LOOP");
				
				if(sfps!=null){
					int ifps = Integer.parseInt(sfps);
					switch(ifps){			
					case 0:			
						mode=0;
						handler.post(new Runnable() {
							public void run() {
								fpsValueButton.setImageResource(R.drawable.ic_3fps);setFps(3);}});		
								
						break;
					case 1:
						mode=1;
						handler.post(new Runnable() {
							public void run() {
								fpsValueButton.setImageResource(R.drawable.ic_6fps);setFps(6);}});		
				
						break;

					case 2:
						mode=2;
						handler.post(new Runnable() {
							public void run() {
								fpsValueButton.setImageResource(R.drawable.ic_12fps);setFps(12);}});		
					
						break;
					case 3:		
						mode=3;
						handler.post(new Runnable() {
							public void run() {
								fpsValueButton.setImageResource(R.drawable.ic_24fps);setFps(24);}});	
						break;
					default: //Log.d(TAG, "wtf invalid fps mode property");
					}					
				}
				
				
				if(smode!=null){
					int imode = Integer.parseInt(smode);
					switch(imode){			
					case 0:			
						mode=0;
						handler.post(new Runnable() {
							public void run() {
								modeButton.setImageResource(R.drawable.ic_columns);}});		
						use_columns=true;					
						use_framenrs=false;
						use_single_page =false;
						break;
					case 1:
						mode=1;
						handler.post(new Runnable() {
							public void run() {
								modeButton.setImageResource(R.drawable.ic_rows);}});	
						use_columns=false;					
						use_framenrs=false;
						use_single_page =false;
						break;

					case 2:
						mode=2;
						handler.post(new Runnable() {
							public void run() {
								modeButton.setImageResource(R.drawable.ic_numbers);}});
						use_columns=true;					
						use_framenrs=true;
						use_single_page =false;
						break;
					case 3:		
						mode=3;
						handler.post(new Runnable() {
							public void run() {
								modeButton.setImageResource(R.drawable.ic_paper);}});
						use_columns=false;					
						use_framenrs=false;
						use_single_page =true;
						break;
					default: //Log.d(TAG, "wtf invalid mode property");
					}					
				}
				if(sflash!=null){
					if (mPreview.mCamera!=null){
						if(sflash.equals("OFF")){
							Camera.Parameters params = mPreview.mCamera.getParameters();
							flash_on=false;
							handler.post(new Runnable() {
								public void run() {
									flashButton.setImageResource(R.drawable.ic_no_flash);}});				
							params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
							mPreview.mCamera.setParameters(params);
						}
						else if (sflash.equals("ON")){
							Camera.Parameters params = mPreview.mCamera.getParameters();
							flash_on=true;
							handler.post(new Runnable() {
								public void run() {
									flashButton.setImageResource(R.drawable.ic_flash_fill);}});
							params.setFlashMode(Camera.Parameters.FLASH_MODE_ON);
							mPreview.mCamera.setParameters(params);
						}
					}		
				}
				if(scolor!=null){
					if(scolor.equals("USE_BW")){
						use_color=false;
						handler.post(new Runnable() {
							public void run() {
								colorButton.setImageResource(R.drawable.ic_grayscale);	}});	

					}
					else if(scolor.equals("USE_COLOR")){
						use_color=true;
						handler.post(new Runnable() {
							public void run() {
								colorButton.setImageResource(R.drawable.ic_color);}});					
					}
				}
				if(sloop!=null){
					if(sloop.equals("LOOP")){
						looping=true;
						handler.post(new Runnable() {
							public void run() {
								loopButton.setImageResource(R.drawable.ic_loop);	}});	

					}
					else if(sloop.equals("ONCE")){
						looping=false;
						handler.post(new Runnable() {
							public void run() {
								loopButton.setImageResource(R.drawable.ic_once);}});					
					}
				}
				try {
					inp.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}

			}else{
				//Log.d(TAG,"no properties found");
				try {
					propert.createNewFile();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			propert = null;		
			inp=null;
			got_assets = true;
			assetSemaphore.release();
			////Log.d(TAG,"asset permits available: "+assetSemaphore.availablePermits());  

		}
	}

	private class SetProperties implements Runnable  {
		public void run(){
			String colormode;
			if (use_color){
				colormode = "USE_COLOR";
			}else{
				colormode = "USE_BW";
			}	
			//Log.d(TAG,"use color " + use_color +  " saving order mode " + colormode);
			String flashmode;			
			if (flash_on){
				flashmode = "ON";
			}else{
				flashmode = "OFF";
			}
			//Log.d(TAG,"use flash " + flash_on +  " saving flash " + flashmode);

			String order="0";
			
			switch(mode){			
			case 0: order="0"; break;
			case 1: order="1"; break;
			case 2: order="2"; break;
			case 3: order="3"; break;	
			}	
			
			String fpsM="2";
			switch(fpsMode){			
			case 0: fpsM="0"; break;
			case 1: fpsM="1"; break;
			case 2: fpsM="2"; break;
			case 3: fpsM="3"; break;	
			}	
			
			String loop;
			if(looping){
				loop="LOOP";
			}else{
				loop="ONCE";
			}
			
			OutputStream outp = null;
			File propert = new File(propLocation);

			try {
				outp = new FileOutputStream(propert);
			} catch (FileNotFoundException e) {
				return;
			}
		
			
			Properties props = new Properties();
			props.setProperty("FPS", fpsM);
			props.setProperty("COLOR", colormode);
			props.setProperty("FLASH", flashmode);
			props.setProperty("MODE", order);
			props.setProperty("LOOP", order);
			//Log.d(TAG,"saved order mode " + order);
			try {
				props.storeToXML(outp,"");
				outp.flush();
				outp.close();
				outp = null;
			} catch (IOException e) {
				return;
			}
		}
	}		

	
	private void copyFile(InputStream in, OutputStream out) throws IOException {
		byte[] buffer = new byte[1024];
		int read;
		while((read = in.read(buffer)) != -1){
			out.write(buffer, 0, read);
		}
	}

	private boolean deleteDirectory(File directory) {
		if(directory.exists()){
			File[] files = directory.listFiles();
			if(null!=files){
				for(int i=0; i<files.length; i++) {
					if(files[i].isDirectory()) {
						deleteDirectory(files[i]);
					}
					else {
						files[i].delete();
					}
				}
			}
		}
		return(directory.delete());
	}


	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent intent) {

		resetActivity();
		fpsButton.setOnClickListener(fpsListener);
		fpsValueButton.setOnClickListener(fpsListener);

		if (requestCode==2){	 // gets the previously created intent
			shots = 0;
			String next_l = "NOPE";
			try{next_l = intent.getStringExtra("nextlayer");}
			catch(NullPointerException e){
				//Log.d(TAG, "null result" );
			}
			//Log.d(TAG, "got string" );
			//Log.d(TAG, "string: " + next_l );


			if(next_l.equals("OPAQUE")){
				//Log.d(TAG, "opaque" );

				next_layer=false;
			}else if (next_l.equals("CUTOUT")){
				//Log.d(TAG, "cutout" );
				ok_to_playback=true;
				next_layer=true;
			}else if(next_l.equals("QUIT")){
				//Log.d(TAG, "quit form child" );

				finish();
			}
			else {
				//Log.d(TAG, "no extra string from display sequence " );
			}			
		}
	}

	private void resetActivity(){
		
		photoQueue = new LinkedBlockingQueue<String>();
		videoQueue = new LinkedBlockingQueue<Bitmap>();
		photoQueueControl = new ArrayList<String>();
		this_batch_nrs = new ArrayList<Integer>();
		queueSemaphore = new Semaphore(-2, true);
		available = new Semaphore(1, true);
		target_width=0;
		target_height=0;

		ok_to_playback=true;
		stop_first_preview=false;
		//executor.shutdownNow();
		
		/*
	   for (Future<?> future:futures) {//TODO catch cancellation exception
	       try {
			future.get();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ExecutionException e) {
			Log.d(TAG, "caught exec exception - future.get");
			e.printStackTrace();
		}
	   }*/
		keep_going=true;
		futures = new LinkedList<Future<?>>();
		ok_to_finish = true;
		handler.post(remove_preview);				
		enablePlay();
		play_waiting = false;
		stop_encoding=false;
		activeProcessingThreads=0;
		//playButton.setOnClickListener(playListener);

	}

	private class WaitForFirstPhoto implements Runnable {	   
		private String directory;
		public WaitForFirstPhoto(String dir){		   
			directory=dir;
		}

		public void run(){
			//Log.d(TAG, "WaitForFirstPhoto, stop_first_preview "+ stop_first_preview);
			// boolean first_photo = false;
			File parentDir = new File(directory);
			boolean first_pic=true;

			while(!stop_first_preview){

				try {
					waitSemaphore.acquire();
				} catch (InterruptedException e1) {
					//ok_to_finish = true;
					return;
				}

				if(parentDir.exists()){			  	 
					File[] files = parentDir.listFiles();
					if(null!=files){ 	  	
						for(int i=0; i<files.length; i++) {	              			                   	
							if(!files[i].isDirectory()){   		  		                    	
								if(files[i].getName().endsWith(".png")){		  		                 			
									String filename=directory+files[i].getName();	
									if(photoQueue.isEmpty()&&first_pic){	
										first_pic=false;
										try {
											//Log.d(TAG, "putting FIRST image " + filename + " to the queue");
											photoQueue.put(filename);
											photoQueueControl.add(filename);
											queueSemaphore.release();
										} catch (InterruptedException e) {
											//ok_to_finish = true;
											return;
										}

										//Log.d(TAG, "notified previewLock");
									}else{
										if(!photoQueueControl.contains(filename)){
											try {
												//Log.d(TAG, "putting image " + filename + " to the queue");
												photoQueue.put(filename);
												photoQueueControl.add(filename);
												queueSemaphore.release();
											} catch (InterruptedException e) {
												//ok_to_finish = true;
												return;
											}
										}

									}                 			   
								}	  		                 		
							}
						}	  		         
					}
				}	  
			}		   
			//ok_to_finish = true;
		}

	}

	private class ProcessPhotos implements Runnable {//TODO interrupt

		private Integer total;
		private String fileName;
		private String dirName;
		private int shot;       

		public  ProcessPhotos(Integer group, String dir, String file, int shots){
			total = group;
			dirName =dir;
			fileName =file;    
			shot = shots;
		}

		public void run(){
			//ok_to_playback=false;
			//Log.d(TAG,"thread " + total +" waiting");          
			ok_to_playback=false;
			try {
				available.acquire();
			} catch (InterruptedException e) {
				return;
			}finally{				
			}
			//Log.d(TAG,"thread " + total +" going ahead, permits "+available.availablePermits());   

			long startTime = System.nanoTime();    
			//Log.d(TAG,"starting native part");       

			//Log.d(TAG, shots +" shots");

			if(shots==1){//start threads to wait for first preview pics
				WaitForFirstPhoto waiting = new WaitForFirstPhoto(dirName);
				waitingForFirstPhoto = new Thread(waiting);
				waitingForFirstPhoto.start();
				ShowFirstPreview show = new ShowFirstPreview();
				showingFirstPreview = new Thread(show);
				showingFirstPreview.start();


				if(!got_assets){
					try {
						//Log.d(TAG, " waiting for assets");
						assetSemaphore.acquire();
					} catch (InterruptedException e) {
						available.release();
						return;
					}finally{
						
					}
				}
				//Log.d(TAG, "got assets");
				FindFrames(fileName, dirName, storage, use_color, next_layer, width, height, 0, 0, use_columns, use_framenrs, use_single_page);
				//Log.d(TAG,"called native: width " + width +", height " +  height + ", target_width DEFAULT 0, target_height 0, bypass " + use_single_page);
			}else{
				FindFrames(fileName, dirName, storage, use_color, next_layer, width, height, target_width, target_height, use_columns, use_framenrs, use_single_page);
				//Log.d(TAG,"called native: width " + width +", height " +  height + ", target_width " + target_width +", target_height"+ target_height + "bypass " + use_single_page);
			}	  

			File parentDir = new File(dirName);

			HashMap<Integer,String> numbered_btms = new HashMap<Integer,String>();

			if(parentDir.exists()){
				File[] files = parentDir.listFiles();
				if(null!=files){ 
					for(int i=0; i<files.length; i++) {
						//Log.d(TAG,"checking "+ files[i].getName());
						if(files[i].isDirectory()) {
						}
						else {
							String filename = files[i].getName();

							if(filename.endsWith(".png")){
								String newname= filename.replace(".png", "");	  		                 			
								String[] parts = newname.split("-");
								for(String s : parts){  		                 			   
									int nr=Integer.parseInt(s);	  		                 				                    		   
									numbered_btms.put(Integer.valueOf(nr),total+ File.separator+files[i].getName());
								}                      			   
							}               
						}
					}	  		         
				}
			} 		  

			BitmapManager.numbered_bitmaps.put(total, numbered_btms);
			//Log.d(TAG,"PUT BITMAPS to manager" );
			if(!global_frame_nrs){
				if(shot==1){
					target_width=0;
					target_height=0;
					BitmapManager.initialize(total);
					//Log.d(TAG,"manager initialized" );
				}else{
					BitmapManager.add_batch(total);
					//Log.d(TAG,"manager added" );
				}

			}
			this_batch_nrs.add(total);
			//Log.d(TAG,"GROUPS so far " + this_batch_nrs );

			long estimatedTime = System.nanoTime() - startTime;
			//Log.d(TAG,"bitmaps processed in " + estimatedTime/1000000000 +"sec");	

			//Log.d(TAG,"available.hasQueuedThreads " + available.hasQueuedThreads());
			//Log.d(TAG,"available.availablePermits " + available.availablePermits()); 		
			//Log.d(TAG,"available. availablePermits " + available.availablePermits());
			//Log.d(TAG,"SHOULD NOTIFY NOW !!!!!!!!!!!");	
			
			activeProcessingThreads--;
			if(!available.hasQueuedThreads()&&activeProcessingThreads==0){	//if it is the last thread, unlock preview		
				//Log.d(TAG,"notifying to start displaying sequence !!!!!!!!!!!! ");   
				ok_to_playback=true;
				synchronized(lock){
					lock.notify();             	
				}
			}	
			available.release();
			//Log.d(TAG,"thread " + total +" exiting"+available.availablePermits()); 
			//Log.d(TAG,"available.hasQueuedThreads " + available.hasQueuedThreads());
			//Log.d(TAG,"available.availablePermits " + available.availablePermits());
		}
	}

	private class ShowFirstPreview implements Runnable{//keep showing new photos 
		public ShowFirstPreview(){		   
		}
		public void run(){
			while(!stop_first_preview){
				try {
					queueSemaphore.acquire();
				} catch (InterruptedException e1) {
					return;
				}
				//Log.d(TAG, "showing first preview!!!!!");
				if((available.hasQueuedThreads()||available.availablePermits()==0)&&play_waiting){	
					//Log.d(TAG,"waitSemaphore. hasQueuedThreads " + waitSemaphore.hasQueuedThreads()); 		
					//Log.d(TAG,"available. availablePermits " + available.availablePermits());
					//Log.d(TAG,"play_waiting " + play_waiting);
					
					handler.post(initialize_pic);
					try{
						String s=null;
						//long startTime = System.nanoTime(); 
						s = photoQueue.take();
						previewBitmap=BitmapFactory.decodeFile(s, options);		
						handler.post(update_pic);

					} catch (InterruptedException e) {//will be shutdown 
						return;
					}				   

				}
			}		   
		}
	} 
	
	Point getSize(Display display) 
	{
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

	public static void onNextPhotoReady(){//will be called from native code
		//Log.d(TAG, "Notified");
		waitSemaphore.release();
		//Log.d(TAG,"waitSemaphore. hasQueuedThreads " + waitSemaphore.hasQueuedThreads()); 		
		//Log.d(TAG,"available. availablePermits " + available.availablePermits());
	}


	final Runnable update_pic = new Runnable() {	
		public void run() {
			update_picture();
		}
	};


	final Runnable remove_preview = new Runnable() {	
		public void run() {
			remove_prev();
		}
	};

	final Runnable remove_camera = new Runnable() {	
		public void run() {
			remove_cam();
		}
	};

	private void remove_cam(){
		mPreview.setVisibility(View.GONE);
	}	

	private void remove_prev(){
		preview_pic.setBackgroundColor(Color.parseColor("#00000000"));
		preview_pic.setImageBitmap(null);
		preview_pic.setVisibility(View.GONE);
		videoQueue.clear();
		//Log.d(TAG, "preview  removed");		
	}


	private void update_picture(){
		preview_pic.setImageBitmap(previewBitmap);
	}	

	final Runnable initialize_pic = new Runnable() {	
		public void run() {
			initialize_picture();
		}
	};

	private void initialize_picture(){
		photoButton.setOnClickListener(null); 			   
		preview_pic.setBackgroundColor(Color.parseColor("#FFFFFF"));		
		preview_pic.setVisibility(View.VISIBLE);
	}	

	private class Encoding implements Callable<String> {
		public String call(){	
			if(shots!=0){
				allgroups = btmpass.getAllGroups();	
				long startTime=0;
				long estimatedTime;
				long waittime;
				double est;
				double est_frrate;
				double interval = 1/fps;
				int inter= (int) Math.round(interval*1000);		
				//Log.d(TAG,"framerate " + fps +" fps, limit " + timerLimit  + " frames, interval " +interval +"sec" );			

				Display display = getWindowManager().getDefaultDisplay();
				Point size = getSize(display);
				if(size.x>size.y){
					width = size.x;
					height = size.y;
				}else{
					width = size.y;
					height = size.x;
				}		

				int limit = btmpass.longestLayer;
				Bitmap bitmap=null;

				int canvas_ww=0;
				int canvas_hh=0;		

				encoder = new VideoEncoder(fps);
				//Log.d(TAG, " longest layer " + limit);
				//allgroups = btmpass.getAllGroups();		

				for(int i=0;i<limit;i++){	
					if(stop_encoding){
						//ok_to_finish = false;
						return null;
					}
					startTime=System.nanoTime();
					int w=0;
					int h=0;
					Canvas frame = null;
					Bitmap cs = null;
					canvas_hh = height;
					canvas_ww = width;

					cs = Bitmap.createBitmap(canvas_ww, canvas_hh, Bitmap.Config.ARGB_8888); 
					frame = new Canvas(cs);

					for (Integer key : allgroups.keySet()){
						//Log.d(TAG, " displaying group " + key + " image " + allgroups.get(key).get(i));
						//TODO cache miss always?
						//bitmap = btmpass.getBitmapFromMemCache(allgroups.get(key).get(i));											
						//if (bitmap == null) {
						//Log.d(TAG, "CACHE MISS");
						bitmap=BitmapFactory.decodeFile(session+allgroups.get(key).get(i), options);			        
						//}else{//Log.d(TAG, "CACHE HIT");
						//}
						w=bitmap.getWidth();
						h=bitmap.getHeight();
						//Log.d(TAG, "w h " +w+"x" +h );		
						frame.drawBitmap(bitmap, (canvas_ww-w)/2, (canvas_hh-h)/2,  null); 					
					}
					Bitmap video_frame = cs.copy(cs.getConfig(), true);
					//OutputStream outStream = null;
					//String filen = session +"temp"+i+".jpg";
					//File file = new File(filen);
					//try {
					//	outStream = new  FileOutputStream(file);
					//} catch (FileNotFoundException e1) {
					//	// TODO Auto-generated catch block
					//	e1.printStackTrace();
					//}
					//video_frame.compress(Bitmap.CompressFormat.JPEG, 100, outStream);
					videoQueue.add(video_frame);
					handler.post(update_preview); //WTF? posting all at once after finishing?

					int[] pixeldata=new int [canvas_ww*canvas_hh];
					cs.getPixels(pixeldata, 0, canvas_ww, 0, 0, canvas_ww, canvas_hh);
					if(i==0){
						//Log.d(TAG,"initializing encoder");
						encoder.initialize(canvas_ww,canvas_hh,fps);
					}
					//Log.d(TAG,"adding frame, index" + i);
					encoder.addFrame(pixeldata);//canvas get pixel data
					pixeldata=null;
					frame=null; 
					cs=null;	 
					bitmap=null;	

					estimatedTime = System.nanoTime() - startTime;
					est = estimatedTime/1000000;
					waittime = (long)(inter-est);
					if(waittime>0){
						try {	 		
							Thread.sleep(waittime);
							//Thread.sleep(2000);
						} catch (InterruptedException e) {
							//Log.d(TAG,"interrupted");
							continue;
						}
					}else{
						//est_frrate=1000/(est);
						//Log.d(TAG, "FRAMERATE: " + est_frrate  + " fps" +" wait " + waittime);
					}
				}
				//Log.d(TAG,"finalizing video");
				encoder.finalizeVid();
				//handler.post(remove_preview);

			}else{
				//Log.d(TAG,"no new images");
			}
			return encoder.webmOutputName;
		}
	}

	final Runnable update_preview = new Runnable() {	
		public void run() {
			update_prev();
		}
	};

	private void update_prev(){
		//preview_pic.invalidate();
		//preview_pic.setImageBitmap(null	);
		//preview_pic.setImageDrawable(null);
		//String filename=null;
		try {
			//filename=videoQueue.take();
			//	Bitmap video_frame=BitmapFactory.decodeFile(filename, options);	
			preview_pic.setImageBitmap(videoQueue.take());
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			return;
		}
		//Log.d(TAG, "preview updated: "+filename);		
	}

	private void setFps(int fpsval){
		DisplaySequenceActivity.fps=fpsval;
		this.fps=fpsval;
		
		switch(fpsval){
		case 3: this.fpsMode=0; break;
		case 6: this.fpsMode=1; break;
		case 12: this.fpsMode=2; break;
		case 24: this.fpsMode=3; break;
		}
	}


}