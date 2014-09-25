package com.penandplay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.TreeMap;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.support.v4.util.LruCache;


public class BitmapManager {
	//TODO chceck if layer has at least one photo to accept
	//TODO change hash map to thread safe concurrent hashmap
	//TODO change length, force some minimal playback duration through layer with n null bitmaps 
	//TODO guess numbers if duplicate / missing
	//null pointer in generate playback bitmaps?
	
	public static int longestLayer =0;
	private static String TAG = "BitmapManager";
	private static Object lock = new Object();
	private static boolean loop = true;
	private static Integer longest=0;
	public static int width;
	public static int height;
	private static double display_ratio; 
	private static ArrayList<String> temp_group;
	private static ArrayList<String> temp_all_groups;

	public static  HashMap<Integer,HashMap<Integer,String>> numbered_bitmaps = new HashMap<Integer,HashMap<Integer,String>>();
	private static HashMap<Integer,ArrayList<String>> ordered_bitmaps = new HashMap<Integer,ArrayList<String>>();
	public static TreeMap<Integer,ArrayList<String>> playback_bitmaps = new TreeMap<Integer,ArrayList<String>>();
	public static  HashMap<Integer,String> loop_preferences =   new HashMap<Integer,String>();
	public static  HashMap<Integer,String> videos =   new HashMap<Integer,String>();

	private static LruCache<Integer, Bitmap> mMemoryCache;
	private static BitmapFactory.Options options;	 	
	//here scale the images
	private static Bitmap bitmap;
	private static Bitmap scaled;
	private static double bw=0;
	private static double bh=0;
	private static double bitmap_ratio;
	private static double scale_ratio_w;
	private static double scale_ratio_h;	
	private static int[] pix;
	private static int final_w;
	private static int final_h;

	public static void clearCache(){
		mMemoryCache.evictAll();
	}
	
	public static void clearAll(){
		//Log.d(TAG, "CLEARING ALL BITMAPS");
		numbered_bitmaps = new HashMap<Integer,HashMap<Integer,String>>();
		ordered_bitmaps = new HashMap<Integer,ArrayList<String>>();
		playback_bitmaps = new TreeMap<Integer,ArrayList<String>>();
		loop_preferences =   new HashMap<Integer,String>();
		videos =   new HashMap<Integer,String>();
		
	}


	public static void createCache(){
		final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
		// Use 1/8th of the available memory for this memory cache.
		final int cacheSize = maxMemory / 8;

		mMemoryCache = new LruCache<Integer, Bitmap>(cacheSize) {
			@Override
			protected int sizeOf(Integer key, Bitmap bitmap) {
				// The cache size will be measured in kilobytes rather than
				// number of items.
				return bitmap.getRowBytes() * bitmap.getHeight() / 1024;
			}
		};
	}			

	// Private constructor prevents instantiation from other classes
	private BitmapManager() {

	}

	/**
	 * SingletonHolder is loaded on the first execution of Singleton.getInstance() 
	 * or the first access to SingletonHolder.INSTANCE, not before.
	 */
	private static class BitmapManagerHolder { 
		private static final BitmapManager  INSTANCE = new BitmapManager ();
	}

	public static BitmapManager  getInstance() {
		createCache();
		return BitmapManagerHolder.INSTANCE;
	}

	public static void addBitmapToMemoryCache(Integer key, Bitmap bitmap) {
		if(key!=null){
			if (getBitmapFromMemCache(key) == null ) {
				mMemoryCache.put(key, bitmap);
				//Log.d(TAG,"added " + key + " to chache");
			}
		}else{
			//Log.d(TAG,"KEY NULL");
		}
	}

	public static Bitmap getBitmapFromMemCache(Integer key) {
		return mMemoryCache.get(key);
	}

	public static void initialize(Integer number){
		//Log.d(TAG,"initializing manager");
		temp_all_groups=new ArrayList<String>();
		reorderBitmaps(number);
		//Log.d(TAG,"reordered");
		temp_all_groups.addAll(temp_group);
		//Log.d(TAG,"added");
		ArrayList<String> layer  = temp_group;


		options = new BitmapFactory.Options();
		options.inJustDecodeBounds = true;
		
		//Log.d(TAG,"layer " + layer);
		
		int nr=0;
		for(String s : layer){
			
			//Log.d(TAG,"decoding  "+DisplaySequenceActivity.session+s);
			if(nr==0){
			//bitmap = BitmapFactory.decodeFile(DisplaySequenceActivity.session+s,options);
			//addBitmapToMemoryCache(s, bitmap);
			//if(nr==0){
				////Log.d(TAG,"getting target size for next batch  "+ bitmap.getWidth() + " x  " + bitmap.getHeight() +  "file " + s);
				bitmap = BitmapFactory.decodeFile(DisplaySequenceActivity.session+s,options);

				PhotoActivity.target_height=options.outHeight;
				PhotoActivity.target_width=options.outWidth;

			}
			nr++;

		}
		options.inJustDecodeBounds = false;
		bitmap=null;
		//Log.d(TAG,"manager initialized ");
		return;
	
	}

	public static void add_batch(Integer number){
		//Log.d(TAG,"adding gr. "  + number);
		reorderBitmaps(number);
		temp_all_groups.addAll(temp_group);
		ArrayList<String>layer = temp_group;
/*
		for(String s : layer){
			//Log.d(TAG,"adding  "+DisplaySequenceActivity.session+s);
			//bitmap = BitmapFactory.decodeFile(DisplaySequenceActivity.session+s,options);
			//addBitmapToMemoryCache(s, bitmap);
		}
*/


		//bitmap=null;
		//Log.d(TAG,"added gr. "  + number);
		return;
	}

	public static void finalize(ArrayList<Integer> numbers){
		
		int groupnr=0;
		for(int nr : numbers){			
			groupnr = nr;
		}			
		//Log.d(TAG,"loop preferences "+ loop_preferences);	
		//loop_preferences.put(groupnr, "LOOP");//TODO get loop preferences
		ordered_bitmaps.put(groupnr, temp_all_groups);//todo		
		//Log.d(TAG,"generating playback btms group" + groupnr);
		generatePlaybackBitmaps(groupnr);
		return;
	}

	public static void reorderBitmaps(int number){
		temp_group=new ArrayList<String>();
		//Log.d(TAG,"reordering ,getting lock");	
		synchronized(lock){
			//Log.d(TAG,"reordering ,got lock");	

			TreeMap<Integer,String> presorted = new TreeMap<Integer,String>();
			TreeMap<Integer,String> fixed = new TreeMap<Integer,String>();
			ArrayList<String> finalized = new ArrayList<String>();

			HashMap<Integer,String> numbered = numbered_bitmaps.get(number);
			for(Entry<Integer,String> entry : numbered.entrySet()) {

				Integer key = entry.getKey();
				String value = entry.getValue();
				////Log.d(TAG,"reordering:" + value);
				if(key>=1000){//written framenumber, gets special treatment later
					int newkey = (key-1000);	
					fixed.put(newkey,value);
				}else{//interpolated number, will be sorted first
					int newkey = key + 1000*number;
					presorted.put(newkey,value);
				}
			}	  


			for(int pnr : presorted.keySet()){
				finalized.add(presorted.get(pnr));					
			}
			for(int fnr : fixed.keySet()){
				finalized.add(fnr,fixed.get(fnr));				
			}
			
			finalized.removeAll(Arrays.asList(null,"")); //TODO
			temp_group=finalized;
		}

		//Log.d(TAG,"reordered ,getting lock");	
	}


	//TODO DO NOT USE
	public static void reorderBitmaps(ArrayList<Integer> numbers){

		//TODO guess numbers if just 1 or 2 are off 
		synchronized(lock){
			////Log.d(TAG,"reordering ,got lock");	

			TreeMap<Integer,String> presorted = new TreeMap<Integer,String>();
			TreeMap<Integer,String> fixed = new TreeMap<Integer,String>();
			ArrayList<String> finalized = new ArrayList<String>();
			int groupnr=0;
			for(int nr : numbers){
				HashMap<Integer,String> numbered = numbered_bitmaps.get(nr);
				for(Entry<Integer,String> entry : numbered.entrySet()) {
					Integer key = entry.getKey();
					String value = entry.getValue();
					if(key>=1000){//written framenumber, gets special treatment later
						int newkey = (key-1000);	
						fixed.put(newkey,value);
					}else{//interpolated number, will be sorted first
						int newkey = key + 1000*nr;
						presorted.put(newkey,value);
					}
				}	  
				groupnr = nr;
			}
			for(int pnr : presorted.keySet()){
				finalized.add(presorted.get(pnr));					
			}
			for(int fnr : fixed.keySet()){
				finalized.add(fnr,fixed.get(fnr));				
			}

			//loop_preferences.put(groupnr, "LOOP");//TODO get loop preferences
			ordered_bitmaps.put(groupnr, finalized);
			//Log.d(TAG,"finalized length " + finalized.size());	
			generatePlaybackBitmaps(groupnr);
			//scaleBitmaps(groupnr);
		}
		System.gc();
		//Log.d(TAG,"Bitmaps processed, video encoded");	
	}

	public static ArrayList<String> getGroup(int gr){
		synchronized(lock){
			return ordered_bitmaps.get(gr);
		}
	}

	public static TreeMap<Integer,ArrayList<String>> getAllGroups(){
		//Log.d(TAG,"getting playback bitmaps " + playback_bitmaps);	
		return playback_bitmaps;
	}




	private static void generatePlaybackBitmaps(Integer groupnr){

		boolean reorder_all=false;
		for(Integer key : ordered_bitmaps.keySet()) {
			ArrayList<String> original_layer = ordered_bitmaps.get(key);
			//Log.d(TAG,"original layer : " + original_layer);
			ArrayList<String> playback_layer = new ArrayList<String>();

			if(original_layer.size()==longestLayer){
				//Log.d(TAG,"equal length");	
				
				playback_layer=original_layer;	
				playback_bitmaps.put(key,playback_layer);
				//Log.d(TAG,"putting into playback bitmaps: " + playback_layer);
				//Log.d(TAG,"playback bitmaps now: " + playback_bitmaps);
			}else if(original_layer.size()>longestLayer){//keep this and loop/prolong all others videos
				//according to their preferences
				//Log.d(TAG,"longer, reorder the rest");	
				
				longestLayer=original_layer.size();
				//Log.d(TAG,"new longest layer " + longestLayer);	
				playback_layer=original_layer;
				//longest = key;
				reorder_all=true;
				playback_bitmaps.put(key,playback_layer);
				//Log.d(TAG,"putting into playback bitmaps: " + playback_layer);
				//Log.d(TAG,"playback bitmaps now: " + playback_bitmaps);

			}else{//loop new video
				//Log.d(TAG,"shorter");	
				int i =0;
				//Log.d(TAG, "setting for group "+ key + " is " + loop_preferences.get(key));
				if(loop_preferences.get(key).equals("LOOP")){
					//Log.d(TAG,"looping");	
					while(i<longestLayer){
						for (String b : original_layer){							
							playback_layer.add(i,b);
							i++;
							//Log.d(TAG,"moving: " + b);
						}
					}
				}else if(loop_preferences.get(key).equals("ONCE")){
					//Log.d(TAG,"prolonging");	
					while(i<longestLayer){
						if (i< original_layer.size()){							
							playback_layer.add(i,original_layer.get(i));
						}else{	
							playback_layer.add(i,original_layer.get(original_layer.size()-1));
						}
						i++;
						////Log.d(TAG,"moving: " + b);
					}
				}
				playback_bitmaps.put(key,playback_layer);
				//Log.d(TAG,"putting into playback bitmaps: " + playback_layer);
				//Log.d(TAG,"playback bitmaps now: " + playback_bitmaps);
			}



		}
		if(reorder_all){
			//Log.d(TAG,"reordering all");	
			for(Integer key : ordered_bitmaps.keySet()) {
				//Log.d(TAG,"reordering layer " + key);	
				if(!(key==groupnr)){

					int i =0;
					playback_bitmaps.remove(key);
					//Log.d(TAG,"removed layer " + key);	
					playback_bitmaps.put(key, new ArrayList<String>());
					//Log.d(TAG,"add new layer as" + key);
					if(loop_preferences.get(key).equals("LOOP")){
						//Log.d(TAG,"looping");
						while(i<longestLayer){
							for (String b : ordered_bitmaps.get(key)){							
								playback_bitmaps.get(key).add(i,b);
								i++;
								//Log.d(TAG,"moving: " + b + " to position " + i);
							}
						}
						//Log.d(TAG,"put into playback bitmaps: " + playback_bitmaps.get(key));
						//Log.d(TAG,"playback bitmaps now: " + playback_bitmaps);
					}else if(loop_preferences.get(key).equals("ONCE")){
						//Log.d(TAG,"prolonging");
						while(i<longestLayer){
							if(i<ordered_bitmaps.get(key).size()){														
								playback_bitmaps.get(key).add(i,ordered_bitmaps.get(key).get(i));
							}else{
								playback_bitmaps.get(key).add(i,ordered_bitmaps.get(key).get(ordered_bitmaps.get(key).size()-1));
							}	
							i++;
							////Log.d(TAG,"moving: " + b + " to position " + i);

						}
						//Log.d(TAG,"put into playback bitmaps: " + playback_bitmaps.get(key));
						//Log.d(TAG,"playback bitmaps now: " + playback_bitmaps);

					}
				}
			}
		}



		for(Integer key : playback_bitmaps.keySet()) {
			//Log.d(TAG,"length of layer "+ key +":" + playback_bitmaps.get(key).size());	

		}

		System.gc();

		//TODO respect the playback settings to loop/clip/mirrorloop/
	}

	public static int calculateInSampleSize(int orig_width, int orig_height, int reqWidth, int reqHeight) {
		// Raw height and width of image

		int inSampleSize = 1;

		if (orig_height > reqHeight || orig_width > reqWidth) {

			int halfHeight = orig_height / 2;
			int halfWidth = orig_width / 2;

			// Calculate the largest inSampleSize value that is a power of 2 and keeps both
			// height and width larger than the requested height and width.
			while ((halfHeight / inSampleSize) > reqHeight
					&& (halfWidth / inSampleSize) > reqWidth) {
				inSampleSize *= 2;
			}
		}

		return inSampleSize;
	}
	//TODO implement if global frame numbers will be used
	public static void scaleBitmaps(int group){
		BitmapFactory.Options options = new BitmapFactory.Options();
		display_ratio=(double)width/(double)height; 



		//here scale the images
		Bitmap bitmap;
		Bitmap scaled;
		double bw=0;
		double bh=0;
		double bitmap_ratio;
		double scale_ratio_w;
		double scale_ratio_h;	



		ArrayList<String> layer  = playback_bitmaps.get(group);
		int nr=0;
		for(String s : layer){//calculate average 
			nr++;
			//Log.d(TAG,"decoding size of "+DisplaySequenceActivity.session+s);
			bitmap = BitmapFactory.decodeFile(DisplaySequenceActivity.session+s,options);
			bh += options.outHeight;
			bw += options.outWidth;
		}
		bw=bw/nr;
		bh=bh/nr;
		//Log.d(TAG,"average width: "+bw+" avg height: "+ bh);
		//Log.d(TAG,"device width: "+width+" device height: "+ height);

		bitmap_ratio = bw/bh;
		//Log.d(TAG,"device ar : "+display_ratio + "image ar: " + bitmap_ratio);
		scale_ratio_w=((double)width)/bw;
		scale_ratio_h=((double)height)/bh;		
		options.inSampleSize=calculateInSampleSize((int)bw,(int)bh,width,height);
		//Log.d(TAG,"in sample size of" +calculateInSampleSize((int)bw,(int)bh,width,height));
		//Log.d(TAG,"width x height " + bw +"x"+bh);
		options.inJustDecodeBounds = false;
		int final_w;
		int final_h;

		if(bitmap_ratio>display_ratio){//fit width to screen, compute height 

			final_w = width;
			final_h=(int)Math.round(bh*scale_ratio_w);
			////Log.d(TAG,"computed height "+(int)Math.round(bh*scale_ratio_w));
			////Log.d(TAG,"image ar after computation  "+(double)width/(int)Math.round(bh*scale_ratio_w));
		}else{//fit height
			final_w=(int)Math.round(bw*scale_ratio_h);
			final_h=height;
			////Log.d(TAG,"computed width "+(int)Math.round(bw*scale_ratio_h));
			////Log.d(TAG,"image ar after computation  "+Math.round(bh*scale_ratio_w)/(double)height);

		}	



		for(String s : layer){
			//Log.d(TAG,"decoding  "+DisplaySequenceActivity.session+s);
			bitmap = BitmapFactory.decodeFile(DisplaySequenceActivity.session+s,options);
			////Log.d(TAG,"scale ratio h & w "+scale_ratio_h + " " + scale_ratio_w);



			scaled = Bitmap.createScaledBitmap(bitmap, final_w, final_h, true);
			//addBitmapToMemoryCache(s, scaled);


		}

		bitmap=null;
		scaled=null;}

	public void removeGroup(Integer i){
		playback_bitmaps.remove(i);
		ordered_bitmaps.remove(i);
		longestLayer =0;
		for(Integer key : ordered_bitmaps.keySet()) {
			ArrayList<String> original_layer = ordered_bitmaps.get(key);
			if(original_layer.size()>longestLayer){
				//Log.d(TAG,"updating longest layer");
				longestLayer=original_layer.size();
			}
		}
		
	}
}
