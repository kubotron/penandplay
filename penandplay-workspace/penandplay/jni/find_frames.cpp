#include <android/log.h>
#include <jni.h>
#include <string>
#include <sstream>
#include "opencv2/objdetect/objdetect.hpp"
#include "opencv2/highgui/highgui.hpp"
#include "opencv2/imgproc/imgproc.hpp"
#include "cv.h"
#include "ml.h"
//non portable headers 
////////////////////////////////////


using namespace cv;
using namespace std;

bool keep_going=true;
bool compute_target_size = true;
bool remove_cyan = true;
bool bg_image=false;
bool top_obscured=false;
bool bottom_obscured=false;
bool left_obscured=false;
bool right_obscured=false;
bool interactive = false;
bool bypass_processing = false;
int original_rows,original_cols,rows_resized,cols_resized;
int v_res_primary=720; //working image v resolution (whole image)
int frame_working_height=720; //default, will be smaller for most
int frame_working_single_page=1080;
//some default valuse
int device_screen_height = 320;
int device_screen_width = 480;
int frame_padding = 5;
int target_w=0; 
int target_h=0;
double avg_horizontal=0;
double vertical = 0;
double avg_vertical=0;	



double device_aspect_ratio;//aspect ratio of device screen
double frame_aspect_ratio=1.7777777777;//default scale of frame
double average_aspect_ratio=1.777777777;
double forced_ratio=1.5;// guessed aspect ratio if paper obscured
double scale_ratio;//scale from original to working image 
double rows_scaled,cols_scaled;


PCA pca;
Mat loadeigenvectors, loadeigenvalues, loadmeans;
CvANN_MLP mlp;
RNG rng( 0xFFFFFFFF );	



Mat src, src_working, src_working_copy, original_size, color_frame, black_frame;
vector<vector<Point2f> > outside_corners;

void correct_orientation();
void remove_cyan_channel();
void resize();
void detect_target();
void negative();
void find_lines();
void find_all_frames(const char* folderName);
bool sort_contours_rows(vector<Point2f> a, vector<Point2f> b);
bool sort_contours_cols(vector<Point2f> a, vector<Point2f> b);
bool sort_digits_rows( const vector<Point>  a, const vector<Point>  b );
vector<Point2f> sortCorners(vector<Point> corners);
Mat formatImageForPCA(Mat data);
void feather(Mat mask);
void equalize_levels(Mat orig);
Size compute_size();
bool the_hard_way();
void add_color_frame();
Point computeIntersect(cv::Vec4i a, cv::Vec4i b);
void resize_save(const char* folder);

void findFrames(const char*, const char *, const char *);
void java_abort();
void java_notify();


///////////////////////////////////////////////////
//non portable part come here 

//configurable
bool use_remove_cyan = true;
bool use_in_color = true;
bool use_cutout_bg = true;
bool use_framerates = false;
bool use_debug_mode = true;
bool use_columns = true;
bool use_framenrs = false;
bool use_ignore_empty_frames = false;
bool use_single_page = false;
jfieldID java_fid;
JNIEnv * java_env;
jobject java_obj;
jclass java_cls;
jmethodID java_mid;

void  add_color_frame(){
}

void java_notify(){
__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "Notifying");
java_env->CallStaticVoidMethod(java_cls,java_mid);}

void java_abort(){
	if (java_env == NULL) {
				////__android_log_print(ANDROID_LOG_DEBUG,"Native Part", "JAVA ENV NULL!");	
	}else if (java_obj == NULL) {
			////__android_log_print(ANDROID_LOG_DEBUG,"Native Part", "JAVA OBJ NULL!");	
	}else if (java_fid == NULL) {
			////__android_log_print(ANDROID_LOG_DEBUG,"Native Part", "JAVA FIELD ID  NULL!");	
	}else{
		keep_going = (bool) java_env->GetStaticBooleanField(java_cls, java_fid);
	}
	if(!keep_going){
			__android_log_print(ANDROID_LOG_DEBUG,"Native Part", "Force stop from java");	
			return;
	}
}



extern "C" {
JNIEXPORT void JNICALL Java_com_penandplay_PhotoActivity_FindFrames(JNIEnv *env, jobject object, jstring imageFile, jstring imageDir, jstring storagePath, jboolean useColor, jboolean cutBG, jint width, jint height, jint target_width, jint target_height, jboolean useColumns, jboolean useFramenrs, jboolean useSinglePage);


}

extern "C"  JNIEXPORT void JNICALL Java_com_penandplay_PhotoActivity_FindFrames(JNIEnv *env, jobject object, jstring imageFile,  jstring imageDir, jstring storagePath, jboolean useColor, jboolean cutBG, jint width, jint height,  jint target_width, jint target_height, jboolean useColumns, jboolean useFramenrs, jboolean useSinglePage)
{   const char* storage;
	storage = env->GetStringUTFChars(storagePath, 0);
	__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "storage dir path: %s", storage);

	

	const char* fileName;
    fileName = env->GetStringUTFChars(imageFile, 0);
    __android_log_print(ANDROID_LOG_DEBUG, "Native Part", "image file name: %s", fileName);

    const char* dirName;
    dirName = env->GetStringUTFChars(imageDir, 0);
    __android_log_print(ANDROID_LOG_DEBUG, "Native Part", "image dir name: %s", dirName);
	java_env = env;	

	java_obj=object;
 	java_cls = env->GetObjectClass(object);
	if (java_cls != NULL) {
		//fid = env->GetFieldID(clazz, "keep_going", "Z");
		java_fid = env->GetStaticFieldID(java_cls, "keep_going", "Z");
	}else{
	    __android_log_print(ANDROID_LOG_DEBUG, "Native Part", "class NULL");
	}
	if(java_fid==NULL){
	    __android_log_print(ANDROID_LOG_DEBUG, "Native Part", "FID not initialized");
	}

	java_mid = env->GetStaticMethodID(java_cls, "onNextPhotoReady", "()V");
  	if (java_mid == 0){
	    __android_log_print(ANDROID_LOG_DEBUG, "Native Part", "MID not initialized");
	}


	use_columns = useColumns;
	use_in_color = useColor;	
	use_cutout_bg = cutBG;
	use_single_page = useSinglePage;	

	//
	bg_image=false;

	if(use_in_color){
	__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "using color");}

	device_screen_height = height;
	device_screen_width = width;
 	target_w = target_width;
	target_h = target_height;

	__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "target w %d , target h %d",target_w, target_h);
	__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "device w %d , device h %d",device_screen_width , device_screen_height);


	findFrames(fileName, dirName, storage);
	__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "finished");

	env->ReleaseStringUTFChars(imageFile, fileName);
	env->ReleaseStringUTFChars(storagePath, storage);
	env->ReleaseStringUTFChars(imageDir, dirName);
   return;
}

//non-portable ends here
/////////////////////////////////////////




void findFrames(const char* fileName, const char* dirName, const char* storage){

	if (target_w == 0 || target_h == 0){
		compute_target_size=true;	
	}
	device_aspect_ratio=(double)device_screen_width/(double)device_screen_height;
	//cout << "device " << device_aspect_ratio << endl;


	

	const char* storageDir = storage;

	std::stringstream mlpString;
	mlpString << storage << "mlp-dig-100-200-400-10-backprop.xml";
	std::string ssmlp = mlpString.str();
	char const* smlp=  ssmlp.c_str();

	std::stringstream pcaString;
	pcaString << storage << "pca_all100.yml";
	std::string sspca = pcaString.str();
	char const* spca = sspca.c_str();

	std::stringstream dirString;
	dirString << dirName;
	std::string ssdir = dirString.str();
	//char const* spca = sspca.c_str();

    	const char* SfileName = fileName;
	src = cv::imread(SfileName, 1);
	if( src.empty() ){
		 ////////__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "imread can't load %s", SfileName);
		////cout<<"no file"<<endl;
	     	 return;
	}else{
	   ////////__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "Ok image %s opened", SfileName);
	}


	if(use_framenrs){	
	   mlp.load(smlp);
	   FileStorage fs1(spca , FileStorage::READ);

	   //TODO check success
	   fs1["Mean"] >> loadmeans;
	   fs1["Eigenvalues"] >> loadeigenvalues;
	   fs1["Eigenvector"] >> loadeigenvectors;
	   fs1.release();
	   ////////__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "pca contents read");
		   pca.mean = loadmeans.clone();
	   pca.eigenvalues = loadeigenvalues.clone();
	   pca.eigenvectors = loadeigenvectors.clone();
	   ////////__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "ok pca ready");
	}
	if(use_single_page){
		use_framenrs = false;
		
		//switched here to disable frameless processing so far in development	
		bg_image = true;
		bypass_processing = true;
	}else{
		bypass_processing = false;
	}
	

	correct_orientation();

	if (!use_in_color&&!bg_image&&remove_cyan&&!bypass_processing){
		remove_cyan_channel();
	}

	if(bypass_processing){
		__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "bypassing");
		if(!use_in_color){
			cvtColor(src, src, CV_RGB2GRAY);
		}
		resize_save(dirName);		
		return;
	}

	src.copyTo(original_size);

	resize();


 	add_color_frame();

	if (src_working.channels()>1){
		cvtColor(src_working, src_working, CV_RGB2GRAY);
	}

	int erosion_size=1;
	Mat element = getStructuringElement( 0,
		                             Size( 2*erosion_size + 1, 2*erosion_size+1 ),
		                             Point( erosion_size, erosion_size ));

	//dilate(src_working,src_working, element,cv::Point(-1,-1) );

	erode(src_working,src_working, element,cv::Point(-1,-1) );
	normalize(src_working,src_working, 0, 255, NORM_MINMAX, CV_8UC1);
	if(!use_single_page){
		adaptiveThreshold(src_working,src_working, 256, ADAPTIVE_THRESH_GAUSSIAN_C , THRESH_BINARY, 37,0 );
		bitwise_not(src_working,src_working);
	}else{//apply canny for single page detection
		
		//Canny(src_working,src_working, 400, 1000, 5 );
		
		//imshow("after canny",src_working);
		

	}
 	////////__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "start finding frames");
	/*	
	if(use_single_page){
		src_working.copyTo(src_working_copy);
		black_frame = Mat::zeros(src_working_copy.rows,src_working_copy.cols, CV_8UC1);
	}
	*/	
	find_all_frames(dirName);
	////////__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "done");

	return;
}

void find_all_frames(const char* folderName){
	

	vector<vector<Point> > contours;		
	vector<Point>  approxCurve;
	vector<Vec4i> hierarchy;
	vector<Point> frame_corners;
	vector<Point2f> frame_corners2f;
	vector<vector<Point2f> > all_corners;
	outside_corners.clear();
	
	Rect r;
	if(!bg_image&&use_single_page){
		__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "drawing lines");
		int offset = 20;	
	line(src_working, Point(offset,2),Point(src_working.cols-offset,2), Scalar(255), 1, 8, 0);//top
	line(src_working_copy, Point(offset,2),Point(src_working.cols-offset,2), Scalar(255), 1, 8, 0);//top
	//line(src_working, Point(1,offset),Point(1,src_working.rows-offset), Scalar(255), 2, 8, 0);//left
	line(src_working,Point(offset,src_working.rows-3),Point(src_working.cols-offset,src_working.rows-3), Scalar(255), 1, 8, 0);
	line(src_working_copy,Point(offset,src_working.rows-3),Point(src_working.cols-offset,src_working.rows-3), Scalar(255), 1, 8, 0);
	//line(src_working, Point(src_working.cols-2,offset),Point(src_working.cols-2,src_working.rows-offset), Scalar(255), 2, 8, 0);//right

	}
	
	//imshow("before contorus",src_working);
	//waitKey(20000);
	findContours(src_working, contours, hierarchy, CV_RETR_LIST, CV_CHAIN_APPROX_SIMPLE, Point(0, 0) );	

 	 
	int frame_minimum_height=(int)cvRound(src_working.rows/20);  
	int frame_minimum_width=(int)cvRound(src_working.cols/20);
	int frame_max_height=(int)cvRound(src_working.rows/2);  
	int frame_max_width=(int)cvRound(src_working.cols/2);

	//int single_minimum_height=(int)cvRound(src_working.rows/4);  
	int single_minimum_width=(int)cvRound(src_working.cols/2);
	//int single_max_height=(int)cvRound(src_working.rows-4);  
	int single_max_width=(int)cvRound(src_working.cols*0.95);
	
	
	double s_angle1,s_angle2,s_angle3,s_angle4;
	double h_angle_toler=8;
	double v_angle_toler=12; 

	if(bg_image){
	__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "bg_image true"); 
	}
	if(use_single_page){
	__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "use_single_page true"); 
	}
	

	//TODO set realistic vert resolution for frame
        if ( !contours.empty() && !hierarchy.empty() ) {
		for( int i = 0; i< contours.size(); i++ ){
 	
		       r = boundingRect( contours[i] );
		       if((!use_single_page && r.width>frame_minimum_width && r.height>frame_minimum_height
			&& r.width<frame_max_width && r.height<frame_max_height)||
			(use_single_page && !bg_image && r.width>single_minimum_width && r.width<single_max_width)){	
			   
				//Mat cnt = Mat(contours[i]);
				approxPolyDP(contours[i], approxCurve, arcLength(contours[i],true)*0.05, true);
				
				if (approxCurve.size()==4){
				       	frame_corners = approxCurve;
					frame_corners2f = sortCorners(frame_corners);
					//top line angle
					s_angle1 = abs( atan2((double)frame_corners2f[0].y - frame_corners2f[1].y,
					               (double)frame_corners2f[0].x - frame_corners2f[1].x)  * 180 / CV_PI);
					//bottom
					s_angle2 = abs( atan2((double)frame_corners2f[2].y - frame_corners2f[3].y,
					               (double)frame_corners2f[2].x - frame_corners2f[3].x)  * 180 / CV_PI);
					//left
					s_angle3 = abs( atan2((double)frame_corners2f[0].y - frame_corners2f[3].y,
					               (double)frame_corners2f[0].x - frame_corners2f[3].x)  * 180 / CV_PI);
					//right
					s_angle4 = abs( atan2((double)frame_corners2f[2].y - frame_corners2f[1].y,
					               (double)frame_corners2f[2].x - frame_corners2f[1].x)  * 180 / CV_PI);

					//check_angles
					if ( ( ( (s_angle1<h_angle_toler)||(s_angle1>180-h_angle_toler) )&&
					       ( (s_angle2<h_angle_toler)||(s_angle2>180-h_angle_toler) ) )&&(
					       ( (s_angle3<90+v_angle_toler)&&(s_angle3>90-v_angle_toler) )&&
					       ( (s_angle4<90+v_angle_toler)&&(s_angle4>90-v_angle_toler) ) ) ){    
						//TODO check aspect ratio 
						all_corners.push_back(frame_corners2f); 
					}else{	__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "rejected"); 
					}
				}else{  
					__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "not quad!");       			
				}
				//just debug
				if (interactive){
					//drawContours( color_frame,contours,i, Scalar(0,0,255), 1, CV_AA,hierarchy,0,Point(0,0));	
				}
				if(use_single_page){	
					//drawContours( black_frame,contours,i, Scalar(255), 1, CV_AA,hierarchy,0,Point(0,0));
				}
			}
			
		}
		__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "all corners %d", all_corners.size());
		
		for( int i = 0; i< all_corners.size(); i++ ){//check if this 
			bool inside= false;
			for( int j = 0; j< all_corners.size(); j++ ){//inside any of of this?	
				if(j!=i){////cout<<"comparing"<<endl;
					if( (   (all_corners[i][0].x>=all_corners[j][0].x)//top left	
   					      &&(all_corners[i][0].y>=all_corners[j][0].y)
					      &&(all_corners[i][2].x<=all_corners[j][2].x)//bottom right			
					      &&(all_corners[i][2].y<=all_corners[j][2].y) )||(
					        (all_corners[i][3].x>=all_corners[j][3].y)//bottom left
					      &&(all_corners[i][3].y<=all_corners[j][3].y) 
					      &&(all_corners[i][1].x<=all_corners[j][1].x)//top right
					      &&(all_corners[i][1].y>=all_corners[j][1].y)
					   ) ) {
					inside=true;
					}
				}
			}
			if(!inside){
				outside_corners.push_back(all_corners[i]);

			}
		}
		if(!use_single_page){
			if(!use_columns){//sort by columns
				sort(outside_corners.begin(), outside_corners.end(),sort_contours_rows);
			}else {//sort by rows
				sort(outside_corners.begin(), outside_corners.end(),sort_contours_cols);
			}
		}
		int nrframes=0;
		if(outside_corners.size()==0){
			//cout<<" no corners"<<endl;	
			//if(!the_hard_way()){
				//cout<<" not found"<<endl;	
				bg_image=true;
			//}
			__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "no corners, setting as BG IMAGE");
		}
		__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "outside corners %d", outside_corners.size());
		
		//computes average frame size and sets the working frame v resolution accordingly	
		if(!bg_image){	
			int nr=0;
			for( int i = 0; i< outside_corners.size(); i++ ){
				Point  tl= outside_corners[i][0] ;
				Point  tr= outside_corners[i][1] ;
				Point  br = outside_corners[i][2];
				Point  bl = outside_corners[i][3];
				//circle( color_frame, tl, 5, Scalar(255,0,0), 1, 8, 0 );
				//circle( color_frame, tr, 5, Scalar(255,0,0), 2, 8, 0 );
				//circle( color_frame, br, 5, Scalar(255,0,0), 3, 8, 0 );
				//circle( color_frame, bl, 5, Scalar(255,0,0), 4, 8, 0 );
				

				nr++;
				avg_vertical+=sqrt(pow((outside_corners[i][0].x-outside_corners[i][3].x),2)+
						pow((outside_corners[i][0].y-outside_corners[i][3].y),2))+
						sqrt(pow((outside_corners[i][2].x-outside_corners[i][1].x),2)+
						pow((outside_corners[i][2].y-outside_corners[i][1].y),2));

				avg_horizontal+=sqrt(pow((outside_corners[i][2].x-outside_corners[i][3].x),2)+
						pow((outside_corners[i][2].y-outside_corners[i][3].y),2))+
						sqrt(pow((outside_corners[i][0].x-outside_corners[i][1].x),2)+
						pow((outside_corners[i][0].y-outside_corners[i][1].y),2));
			
			}

			avg_horizontal=avg_horizontal/(2*nr);
			avg_vertical=avg_vertical/(2*nr);
			average_aspect_ratio = avg_horizontal/avg_vertical;
			vertical=avg_vertical*scale_ratio;
			//TODO	set according to device inclination

		}else{	__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "BG IMAGE, forcing frame size");
			avg_horizontal=original_size.cols;
			avg_vertical=original_size.rows;
			average_aspect_ratio = (double)avg_horizontal/(double)avg_vertical;
			vertical=avg_vertical;

		}

			

		
		//cout << "vertical: " << vertical << endl;		


		if((int)cvRound(vertical)<720){
			frame_working_height= (int)cvRound(vertical);
			
		}
		//if(use_single_page&&frame_working_height>frame_working_single_page){ //default, will be smaller for most
		//	frame_working_height=frame_working_single_page;
			////__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "single page, setting frame_working_height to %d", frame_working_single_page);
		//}

		
		//PROCESSING INDIVIDUAL FRAMES FROM HERE
			
		
		for( int i = 0; i< outside_corners.size(); i++ ){
			top_obscured = false;
			bottom_obscured = false;
			left_obscured = false;
			right_obscured = false;		

			try{
			nrframes++;
					
			
			//enlarge frame 20% top right to get marks & framenrs
			double top_length;
			double bottom_length;
			double left_length;
			double right_length;

top_length=sqrt(pow((outside_corners[i][0].x-outside_corners[i][1].x),2)+pow((outside_corners[i][0].y-outside_corners[i][1].y),2));
bottom_length=sqrt(pow((outside_corners[i][2].x-outside_corners[i][3].x),2)+pow((outside_corners[i][2].y-outside_corners[i][3].y),2));
left_length=sqrt(pow((outside_corners[i][0].x-outside_corners[i][3].x),2)+pow((outside_corners[i][0].y-outside_corners[i][3].y),2));
right_length=sqrt(pow((outside_corners[i][2].x-outside_corners[i][1].x),2)+pow((outside_corners[i][2].y-outside_corners[i][1].y),2));

			frame_aspect_ratio=((top_length+bottom_length)/2)/((left_length+right_length)/2);
			

			double frmnrs_width_bottom=bottom_length*0.3;
			double frmnrs_width_top=top_length*0.3;

			double framerates_height_left=left_length*0.2;
			double framerates_height_right=right_length*0.2;

			vector<Point2f>  frame_corners2f_big;
			vector<Point2f>  frameNR_corners2f_big;
			Rect framerates_rect;

			//PROJECT FRAME CORNERS IN BIG IMAGE
				
			if (!bg_image&&use_single_page&&outside_corners.size()==1){
				if((outside_corners[0][0].y<=4)&&(outside_corners[0][1].y<=4)){
					top_obscured = true;
				}
				if((outside_corners[0][2].y>=src_working.rows-4)&&(outside_corners[0][3].y>=src_working.rows-4)){
					bottom_obscured = true;
				}
				if((outside_corners[0][0].y<=2)&&(outside_corners[0][3].y<=2)){//TODO
					left_obscured = true;
				}
				if((outside_corners[0][1].y>=src_working.cols-2)&&(outside_corners[0][2].y>=src_working.cols-2)){//TODO
					right_obscured = true;
				}



			}
			if(top_obscured){//guess the top points
				////__android_log_print(ANDROID_LOG_DEBUG,"Native Part", "guessing bottom line"); 	
				frame_aspect_ratio=forced_ratio;
				average_aspect_ratio=forced_ratio;
				//cout<<"top_obscured " <<endl;
				 for(int j =0;j<outside_corners[i].size();j++){
					
					if(j==0){//top left
						Point2f pt;
						double angle_left = ( atan2(outside_corners[i][0].y - outside_corners[i][3].y,
					               outside_corners[i][0].x - outside_corners[i][3].x) );
						//cout << "angle left: "<< angle_left << endl;

						pt.x = (outside_corners[i][3].x + (bottom_length/forced_ratio)*cos(angle_left))*scale_ratio;					
						pt.y = (outside_corners[i][3].y + (bottom_length/forced_ratio)*sin(angle_left))*scale_ratio;
					        frame_corners2f_big.push_back(pt);

						//cout << "pt.x: "<< pt.x << "pt.y "<< pt.y << endl;
						//circle( original_size, pt, 5, Scalar(0,0,255), 2, 8, 0 );
						
					}
					if(j==1){//top right
						Point2f pt; 

						double angle_right = ( atan2(outside_corners[i][1].y - outside_corners[i][2].y,
					               outside_corners[i][1].x - outside_corners[i][2].x) ); 
						//cout << "angle right: "<< angle_right << endl;

						pt.x = (outside_corners[i][2].x + bottom_length/forced_ratio*cos(angle_right))*scale_ratio;					
						pt.y = (outside_corners[i][2].y + bottom_length/forced_ratio*sin(angle_right))*scale_ratio;
					    

						frame_corners2f_big.push_back(pt);
 						//circle( original_size, pt, 5, Scalar(0,255,0), 2, 8, 0 );
						//cout << "pt.x: "<< pt.x << "pt.y "<< pt.y << endl;
						
						////cout<<"guessed point "<< pt <<endl;
						
					}
					if(j==2){//bottom right is good
						Point2f pt; 
						pt.x = (outside_corners[i][j].x )* scale_ratio;
						pt.y = (outside_corners[i][j].y )* scale_ratio;
						frame_corners2f_big.push_back(pt);		
				
					}
					if(j==3){//bottom left is good
						Point2f pt; 
						pt.x = (outside_corners[i][j].x )* scale_ratio;
						pt.y = (outside_corners[i][j].y )* scale_ratio;
						frame_corners2f_big.push_back(pt);	
	
					}
				}
				
			}else if(bottom_obscured){
				////__android_log_print(ANDROID_LOG_DEBUG,"Native Part", "guessing bottom line"); 	
				frame_aspect_ratio=forced_ratio;
				average_aspect_ratio=forced_ratio;
				//cout<<"bottom_obscured " <<endl;
				for(int j =0;j<outside_corners[i].size();j++){
					if(j==0){//top left
						Point2f pt; 
						pt.x = (outside_corners[i][j].x )* scale_ratio;
						pt.y = (outside_corners[i][j].y )* scale_ratio;
						frame_corners2f_big.push_back(pt);	
							
					}
					if(j==1){//top right
						Point2f pt; 
						pt.x = (outside_corners[i][j].x )* scale_ratio;
						pt.y = (outside_corners[i][j].y )* scale_ratio;
						frame_corners2f_big.push_back(pt);	
					}//Guess bottom points
					if(j==2){//bottom right
						Point2f pt; 
						double angle_right = ( atan2(outside_corners[i][2].y - outside_corners[i][1].y,
					               outside_corners[i][2].x - outside_corners[i][1].x) ); 

						pt.x = (outside_corners[i][1].x + bottom_length/forced_ratio*cos(angle_right))*scale_ratio;					
						pt.y = (outside_corners[i][1].y + bottom_length/forced_ratio*sin(angle_right))*scale_ratio;
					    
						frame_corners2f_big.push_back(pt);	
						//cout << "angle right: "<< angle_right << endl;
						//circle( original_size, pt, 5, Scalar(0,0,255), 2, 8, 0 );	
						//cout << "pt.x: "<< pt.x << "pt.y "<< pt.y << endl;
				
					}
					if(j==3){//bottom left
						Point2f pt; 

						double angle_left = ( atan2(outside_corners[i][3].y - outside_corners[i][0].y,
					               outside_corners[i][3].x - outside_corners[i][0].x) );

						pt.x = (outside_corners[i][0].x + bottom_length/forced_ratio*cos(angle_left))*scale_ratio;					
						pt.y = (outside_corners[i][0].y + bottom_length/forced_ratio*sin(angle_left))*scale_ratio;
						frame_corners2f_big.push_back(pt);	

						//cout << "angle left: "<< angle_left << endl;
						//circle( original_size, pt, 5, Scalar(0,255,0), 2, 8, 0 );	
						//cout << "pt.x: "<< pt.x << "pt.y "<< pt.y << endl;	

 					
	
					}
				}
				
				
			}else if (left_obscured){
				//TODO 
				//cout<<"left_obscured " <<endl;
			}else if (right_obscured){
				//TODO
				//cout<<"right_obscured "<<endl;
			}else{
				for(int j =0;j<outside_corners[i].size();j++){
					
					if(j==0){//top left
						Point2f pt; 
						pt.x = (outside_corners[i][j].x )* scale_ratio;
						pt.y = (outside_corners[i][j].y )* scale_ratio;
						frame_corners2f_big.push_back(pt);	
						framerates_rect.x=pt.x;		
						if((pt.y-framerates_height_left* scale_ratio)<0){
							framerates_rect.y=pt.y-framerates_height_left* scale_ratio;
						}else{
							framerates_rect.y=0;
						}		
					}
					if(j==1){//top right
						Point2f pt; 
						pt.x = (outside_corners[i][j].x )* scale_ratio;
						pt.y = (outside_corners[i][j].y )* scale_ratio;
						frame_corners2f_big.push_back(pt);	
					}
					if(j==2){//bottom right
						Point2f pt; 
						pt.x = (outside_corners[i][j].x )* scale_ratio;
						pt.y = (outside_corners[i][j].y )* scale_ratio;
						frame_corners2f_big.push_back(pt);		
				
					}
					if(j==3){//bottom left
						Point2f pt; 
						pt.x = (outside_corners[i][j].x )* scale_ratio;
						pt.y = (outside_corners[i][j].y )* scale_ratio;
						frame_corners2f_big.push_back(pt);	
	
					}
				}
			
			}
			//imshow("color",color_frame);
			//imshow("pp",src_working);
			//waitKey(10000);

			double frnr_clearance_top = frmnrs_width_top/10;
			double frnr_clearance_bottom = frmnrs_width_bottom*0.1;
		

			frameNR_corners2f_big;	
				
			//Project framenrs to big image
			if(!use_single_page){
				for(int j =0;j<4;j++){
					if(j==0){//top left
						Point2f pt; 
						pt.x=frame_corners2f_big[1].x+(frnr_clearance_top*scale_ratio);
						pt.y=frame_corners2f_big[1].y;
						frameNR_corners2f_big.push_back(pt);						
					}
					if(j==1){//top right 
						Point2f pt; 
						if((frame_corners2f_big[1].x+(frmnrs_width_top*scale_ratio))>=original_size.cols-1){
							pt.x=original_size.cols-1;
						}else{
							pt.x=cvRound(frame_corners2f_big[1].x+(frmnrs_width_top*scale_ratio));
						}
						pt.y=frame_corners2f_big[1].y;
						frameNR_corners2f_big.push_back(pt);	
					}
					if(j==2){//bottom right
						Point2f pt; 
						if(frame_corners2f_big[2].x+frmnrs_width_bottom*scale_ratio>=original_size.cols-1){
							pt.x=original_size.cols-1;
						}else{
							pt.x=cvRound(frame_corners2f_big[2].x+frmnrs_width_bottom*scale_ratio);
						}
						pt.y=frame_corners2f_big[2].y;
						frameNR_corners2f_big.push_back(pt);	

					}
					if(j==3){//bottom left
						Point2f pt; 
						pt.x=frame_corners2f_big[2].x+frnr_clearance_bottom*scale_ratio;
						pt.y=frame_corners2f_big[2].y;
						frameNR_corners2f_big.push_back(pt);	
					}
				}

			}				
			

			
			//if(!frame_working_height+2*frame_padding==0)
			Mat frame_working = Mat(frame_working_height+2*frame_padding,(int)cvRound((frame_working_height+2*frame_padding)*frame_aspect_ratio), CV_8UC1);
			Mat framenumbers;
			if (!use_single_page){
				framenumbers = Mat(frame_working_height,(int)cvRound(frmnrs_width_bottom*scale_ratio), CV_8UC1);
			}


			vector<Point2f> frame_working_pts;
			vector<Point2f> framenumbers_pts;
			
			frame_working_pts.push_back(Point2f(0, 0));
			frame_working_pts.push_back(Point2f(frame_working.cols, 0));
			frame_working_pts.push_back(Point2f(frame_working.cols,frame_working.rows));
			frame_working_pts.push_back(Point2f(0, frame_working.rows));

			if (!use_single_page){				
				framenumbers_pts.push_back(Point2f(0, 0));
				framenumbers_pts.push_back(Point2f(framenumbers.cols, 0));
				framenumbers_pts.push_back(Point2f(framenumbers.cols,framenumbers.rows));
				framenumbers_pts.push_back(Point2f(0, framenumbers.rows));
			}

			//cout << "frame points source"<< endl <<   frame_corners2f_big <<endl << endl;
			////cout << "frame points dest"<< endl <<   frame_working_pts <<endl << endl;

			////cout << "framenumbers points " << endl << frameNR_corners2f_big <<endl << endl;
			////cout << "framenumbers points " << endl << frameNR_corners2f_big <<endl << endl;
			
		
			Mat transmtx_big = getPerspectiveTransform(frame_corners2f_big, frame_working_pts);
			Mat transmtx_frames;
			if (!use_single_page){
				transmtx_frames = getPerspectiveTransform(frameNR_corners2f_big, framenumbers_pts);
			}

			
			if(use_framerates&&!use_single_page){//TODO
				
				framerates_rect.height=(int)cvRound(left_length*0.2*scale_ratio);
				framerates_rect.width=(int)cvRound(top_length*scale_ratio);
				Mat framerates (original_size,framerates_rect);

				vector<vector<Point> > raw_frates;
				vector<vector<Point> > frates;
				vector<Vec4i> f_hierarchy;
				Mat show_framerates; 
				framerates.copyTo(show_framerates);
				cvtColor(show_framerates,show_framerates,CV_GRAY2RGB);		
	
				Canny(framerates, framerates, 300, 800, 5 );
				//TODO chceck this
			
//				adaptiveThreshold(framerates, framerates, 256, ADAPTIVE_THRESH_GAUSSIAN_C , THRESH_BINARY, 37,0 );
//				bitwise_not(framerates,framerates);				
				findContours(framerates, raw_frates, f_hierarchy, CV_RETR_TREE, CV_CHAIN_APPROX_SIMPLE, Point(0, 0) );

				if ( !raw_frates.empty() ) {
					int dig_max_w=(int)cvRound(framerates.cols/20);			
					int dig_min_w=(int)cvRound(framerates.cols/40);
					int dig_max_h=(int)cvRound(framerates.rows/5);
					int dig_min_h=(int)cvRound(framerates.rows/30);
					
					for ( int i=0; i<raw_frates.size(); i++ ) {
							
						Rect dig = boundingRect( raw_frates[i]);
						////cout<<"bound "<<dig<<endl;	
						if ((dig.width>dig_min_w)&&(dig.width<dig_max_w)&&
						    (dig.height>dig_min_h)&&(dig.height<dig_max_h)){	
							frates.push_back(raw_frates[i]);
						}else{
						// rejected 

						}
						//drawContours(show_framerates,  raw_frates,i, Scalar(0,0,255), 1, CV_AA,f_hierarchy,0,Point(0,0));	
					}
					if ( !frates.empty() ) {
				 	double fr = 24/frates.size();

					}
				}
				

			}						



			warpPerspective(original_size, frame_working, transmtx_big, frame_working.size());
			if (!use_single_page){			
				warpPerspective(original_size, framenumbers, transmtx_frames, framenumbers.size());
			}

			Mat big_frame;
			frame_working.copyTo(big_frame);
			
			if(big_frame.channels()>1){
				cvtColor(big_frame,big_frame,CV_RGB2GRAY);
			}		
			//now we should have 720p frame
			Rect image_area_rect (frame_padding,(int)cvRound(frame_padding*frame_aspect_ratio),big_frame.cols-2*frame_padding,big_frame.rows-(int)cvRound(2*frame_padding*frame_aspect_ratio));

			Mat image_area_show(frame_working,image_area_rect);

			Mat image_area(big_frame,image_area_rect);


			bool fr_nr_used = false;
			bool empty_frame = false;		
			vector<int> real_numbers;

			//find digits
			if(use_framenrs){	
				//Mat framenumbers (big_frame,framenumbers_rect); 
				vector<vector<Point> > digits;
				vector<vector<Point> > digits_filtered;	
			
				vector<Vec4i> d_hierarchy;
				if(framenumbers.channels()>1){
					cvtColor(framenumbers,framenumbers,CV_RGB2GRAY);
				}
				normalize(framenumbers,framenumbers, 0, 255, NORM_MINMAX, CV_8UC1);
				
				Mat digit_recog;
				adaptiveThreshold(framenumbers, framenumbers, 256, ADAPTIVE_THRESH_GAUSSIAN_C , THRESH_BINARY, 37,0 );
				bitwise_not(framenumbers,framenumbers);
				framenumbers.copyTo(digit_recog);

				findContours(framenumbers, digits, d_hierarchy, CV_RETR_EXTERNAL, CV_CHAIN_APPROX_SIMPLE, Point(0, 0) );
				//set the sanity check for digits 
				int dig_max_w=(int)cvRound(framenumbers.cols*0.8);			
				int dig_min_w=(int)cvRound(framenumbers.cols/15);
				int dig_max_h=(int)cvRound(framenumbers.rows/5);
				int dig_min_h=(int)cvRound(framenumbers.rows/12);

			

				if ( !digits.empty() ) {
					for ( int i=0; i<digits.size(); i++ ) {
							
						Rect dig = boundingRect( digits[i]);
						////cout<<"bound "<<dig<<endl;	
						if ((dig.width>dig_min_w)&&(dig.width<dig_max_w)&&
						    (dig.height>dig_min_h)&&(dig.height<dig_max_h)){	
							Mat digit (digit_recog,dig); 
							Scalar avgcolor = mean(digit);
							float average_color = avgcolor[0]; 
							float ratio = (int)cvRound(dig.height/dig.width);
							if (!((average_color> 160)&&(ratio<4))){//blacked out digit if not 1 
								digits_filtered.push_back(digits[i]);

							}

						}else{
						// rejected 			
						}



					}
			

					

					if ( !digits_filtered.empty() ) {
						vector<vector <int> > numbers;

						sort(digits_filtered.begin(), digits_filtered.end(),sort_digits_rows);
						int average_h = 0;
						int n = 0;
						for ( int i=0; i<digits_filtered.size(); i++ ) {	
							Rect dig = boundingRect( digits_filtered[i]);
							average_h += dig.height;
							n++;
						}
				
						int avg_h = (int)cvRound(average_h/n++);
						int previous_h = 0;
						int jump=0;
						vector <vector<vector<Point> > > digits_rows;
						vector<vector<Point> > * row = new vector<vector<Point> >();
						int rows=0;
						for ( int i=0; i<digits_filtered.size(); i++ ){
							 Rect dig = boundingRect( digits_filtered[i]);
							if(i==0){
								(*row).push_back(digits_filtered[i]);
								previous_h = dig.y;
							}else{
							       	jump=dig.y - previous_h;
								//check if new row started
								if(jump > (0.8*avg_h)){
									digits_rows.push_back(*row);
									delete row;
									row = new vector<vector<Point> >();
									(*row).push_back(digits_filtered[i]);
								}else{	
									(*row).push_back(digits_filtered[i]);
								}
								previous_h = dig.y;
							}
							if(i==digits_filtered.size()-1){
								digits_rows.push_back(*row);
								delete row;
							}
				
						}

						for ( int i=0; i<digits_rows.size(); i++ ){
							vector<int> number;
							for ( int j=0; j<digits_rows[i].size(); j++ ){
								Rect dig = boundingRect( digits_rows[i][j]);
								Mat digit = Mat::zeros(dig.height,dig.width, CV_8UC1);
								drawContours(digit,  digits_rows[i],j, Scalar(255,255,255), 
										CV_FILLED, CV_AA,noArray(),0,Point(-dig.x,-dig.y));
				 				Mat box = Mat::zeros(28, 28, CV_8UC1);
				
								double sample=20;
								double sratio=0;
								if (digit.cols>=digit.rows){
									sratio=sample/digit.cols;
									resize(digit, digit, Size((int)sample,	
											(int)digit.rows*sratio),0,0,INTER_CUBIC);
									digit.copyTo(box(Rect(4, (int)(28-digit.rows)/2, digit.cols, digit.rows)));
								}
								else {
									sratio=sample/digit.rows;
									resize(digit,digit, Size((int)digit.cols*sratio,
											(int)sample),0,0,INTER_CUBIC);
									digit.copyTo(box(Rect((int)(28-digit.cols)/2,4, digit.cols, digit.rows)));
								}


								//Reshape and stack images into a rowMatrix
								Mat data = formatImageForPCA(box);
								Mat point = pca.project(data); 
								// project into the eigenspace, thus the image becomes a "point"
								//Mat reconstruction = pca.backProject(point); // re-create the image from the "point"
								//reconstruction = reconstruction.reshape(box.channels(), 28);

								Mat  mlp_response = Mat( 1, 10, CV_32F );
								mlp.predict( point, mlp_response );
								Point maxIdx; Point smaxIdx;
								minMaxLoc(mlp_response, NULL, NULL, NULL, &maxIdx);
								int first =  maxIdx.x;
								float first_r = mlp_response.at<float>(maxIdx.x);
			/*					
								mlp_response.at<float>(maxIdx.x) = 0;
								minMaxLoc(mlp_response, NULL, NULL, NULL, &smaxIdx);
								float second_r = mlp_response.at<float>(smaxIdx.x);
								int second =  smaxIdx.x;
			*/					number.push_back(first);

								////imshow("digit",box);	
								////waitKey(2000);


						
							}
							numbers.push_back(number);
						}
				
								

						for ( int i=0; i<numbers.size(); i++ ){//make sense of the numbers

							if (numbers[i].size()==3){
								int num = 100*numbers[i][0] + 10*numbers[i][1] + numbers[i][2];
								real_numbers.push_back(num);
								fr_nr_used = true;
							}
							else if (numbers[i].size()==2){
								int num = 10*numbers[i][0] + numbers[i][1];
								real_numbers.push_back(num);
								fr_nr_used = true;
							}
							else if (numbers[i].size()==1){	
								int num = numbers[i][0];
								real_numbers.push_back(num);
								fr_nr_used = true;
							}
							else {// ignore 
						
							}
				
						}
					}
				}
			}

			//here process individual frames
			int erosion_size_img=2;
			Mat element_img = getStructuringElement( 2,
                                     Size( 2*erosion_size_img + 1, 2*erosion_size_img+1 ),
                                     Point( erosion_size_img, erosion_size_img ));
			

			//normalize(image_area,image_area, 0, 255, NORM_MINMAX, CV_8UC1);

			Mat image_working;
			if(!use_in_color){				
				normalize(image_area_show,image_area_show,0, 255, NORM_MINMAX, CV_8UC1);
			}else{
				equalize_levels(image_area_show);
			}

			Mat transparent;
			if (use_cutout_bg){
				vector<vector<Point> > img_contours;
				vector<Vec4i> img_hierarchy;
				
				image_area.copyTo(image_working);


				Mat mask;
				if(!use_in_color){
					mask = Mat::zeros(image_working.rows,image_working.cols, CV_8UC1);
				}else{
					mask = Mat::zeros(image_working.rows,image_working.cols, CV_8UC3);				
				}	
 	                        transparent = Mat( image_working.rows,image_working.cols, CV_8UC4, Scalar(0,0,0,0) );
//				Mat transparent( image_working.rows,image_working.cols, CV_8UC4 );
				

				
				
		

				Canny(image_working,image_working, 300, 900, 5 );
				//imshow("after canny", image_working);	
				dilate(image_working,image_working, element_img,cv::Point(-1,-1) );
				//imshow("after dilation", image_working);
				erode(image_working,image_working, element_img,cv::Point(-1,-1) );
				//imshow("after erosion", image_working);
				//imshow("pp", image_working);	


				//adaptiveThreshold(image_working,image_working, 256, ADAPTIVE_THRESH_GAUSSIAN_C , THRESH_BINARY, 37,0 );
				//bitwise_not(image_working,image_working);

				int border_clearance=10;
				//cheating lines to help out detect contours on border 
				//top
				line(image_working, Point(border_clearance,2), 
					Point(image_working.cols-border_clearance,2), Scalar(255), 1, 8, 0);
				//bottom
				line(image_working, Point(border_clearance,image_working.rows-2), 	
					Point(image_working.cols-border_clearance,image_working.rows-2), Scalar(255), 1, 8, 0);
				//left
				line(image_working, Point(2,border_clearance), 
					Point(2,image_working.rows-border_clearance), Scalar(255), 1, 8, 0);
				//right
				line(image_working, Point(image_working.cols-2,border_clearance), 
					Point(image_working.cols-2,image_working.rows-border_clearance), Scalar(255), 1, 8, 0);
			
				//imshow("after lines", image_working);


				findContours(image_working, img_contours, img_hierarchy, CV_RETR_EXTERNAL, CV_CHAIN_APPROX_SIMPLE, Point(0, 0) );	 	 
				if(! img_contours.empty()){
					for ( int i=0; i<img_contours.size(); i++ ){
						if(!use_in_color){
							drawContours(mask,  img_contours,i, Scalar(255), 
							CV_FILLED, CV_AA,img_hierarchy,0,Point(0,0));
						}else{ 
							drawContours(mask,  img_contours,i, Scalar(255,0,0), 
							CV_FILLED, CV_AA,img_hierarchy,0,Point(0,0));
	
						}
					
					}
				}
				else{
					empty_frame=true;
				}
				//imshow("mask", mask);
				//waitKey(20000);

				//remove the "cheating" lines	
				line(mask, Point(border_clearance,2), 
					Point(image_working.cols-border_clearance,2), Scalar(0), 1, 8, 0);
				//bottom
				line(mask, Point(border_clearance,image_working.rows-2), 	
					Point(image_working.cols-border_clearance,image_working.rows-2), Scalar(0), 1, 8, 0);
				//left
				line(mask, Point(2,border_clearance), 
					Point(2,image_working.rows-border_clearance), Scalar(0), 1, 8, 0);
				//right
				line(mask, Point(image_working.cols-2,border_clearance), 
					Point(image_working.cols-2,image_working.rows-border_clearance), Scalar(0), 1, 8, 0);
			

				GaussianBlur(mask, mask, Size( 1, 1),0,0);
				
				

				Mat srcImg[] = {image_area_show, mask};
				////imshow("mask",mask);
				////imshow("image_area",image_area);

				////cout << "channels " << image_area.channels() << endl;

				////waitKey(2000);

				if(!use_in_color){
					//TODO check for color images
					
					int from_to[] = { 0,0, 0,1, 0,2, 1,3 };
					mixChannels( srcImg, 2, &transparent, 1,
							    from_to,4);
				}else{
					int from_to[] = { 0,0, 1,1, 2,2, 3,3 };
					mixChannels( srcImg, 2, &transparent, 1,
							 from_to, 4);	
				}
			

				
			}else{//dont cut contours
				transparent = image_area_show;
			

			}

			ostringstream ustr;
				
			if (use_framenrs && fr_nr_used && (!real_numbers.empty())){
				sort(real_numbers.begin(), real_numbers.end());
				for ( int i=0; i<real_numbers.size(); i++ ){
					//
					int padded = real_numbers[i];
					if(!(i==0)){ustr <<"-";}
					if (padded>99){	ustr <<"1" << padded;}
					else if (padded>9){ustr <<"1"<<"0"<< padded;}
					else{ustr <<"1"<<"00"<< padded;}
				}
				ustr<<".png";
			}
			else {	
				int padded = nrframes; 
				//only goes to 12 				
				if (padded>9){ustr <<"0"<< padded <<".png";}
				else{ustr <<"00"<< padded <<".png";}
			}	
			string un=ustr.str();	
			const char* file_u = un.c_str();
			////////__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "file name %s", file_u);
			
			////imshow("saving",transparent);
			ostringstream  path;
			path << folderName  << file_u;

			ostringstream  orig_path;
			orig_path << folderName << "orig" << file_u;

			string pth=path.str();	
			const char* file_n = pth.c_str();
			string opth=orig_path.str();	
			const char* file_o = opth.c_str();


			Size size;
			if(!compute_target_size){
				size = Size(target_w,target_h);
			}else{
				size = compute_size();
			}
			resize(transparent,transparent,size);

			//////__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "saving %s", file_n);
			////////__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "saving original %s", file_o);		
			
			bool success = false;
			if(!empty_frame||use_ignore_empty_frames){
				if(!use_in_color){				
				equalize_levels(transparent);
				}
			
				success = imwrite(file_n,transparent);
				if(use_single_page){
					////__android_log_print(ANDROID_LOG_DEBUG,"Native Part", "single page saving image: width %d, height %d", transparent.cols, transparent.rows);	
					imwrite(file_n,transparent);

					//just debug					
					//imwrite(file_o,original_size);
				}
			}
			if(success){			
				java_notify();
				   ////////__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "success");
			}
			else{
				   ////////__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "FAILED!");
			}
			////imshow("pp",image_area);
			////waitKey(60000);	
			java_abort();
				
			}catch(std::exception& e){
				__android_log_print(ANDROID_LOG_DEBUG,"Native Part", "EXCEPTION IN NATIVE CODE: %s",e.what());
				java_abort();
				//bg_image=true;
				break;
				//cout << "EXCEPTION" << e.what() << endl;
		
			}//end individual frames 	
		
		}
		if(bg_image){
			 java_abort();
			//////__android_log_print(ANDROID_LOG_DEBUG,"Native Part", "no frame found, saving original image ");		
			ostringstream  path;
			path << folderName  << "1.png";

			ostringstream  orig_path;
			orig_path << folderName << "orig/" << "1.png";

			string pth=path.str();	
			const char* file_n = pth.c_str();
			string opth=orig_path.str();	
			const char* file_o = opth.c_str();
			////////__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "saving %s", file_n);
			////////__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "saving original %s", file_o);	
			
			Size size;
			if(!compute_target_size){
				//__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "no computing,resizing bg image, set width %d, height %d", target_w, target_h);
				size = Size(target_w,target_h);
			}else{
			
				size = compute_size();
			}
			resize(original_size,original_size,size);
			////__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "resizing bg image, computed width %d, height %d", size.width, size.height);

			bool success = imwrite(file_n,original_size);
			//imwrite(file_o,original_size);
			if (success){
				java_notify();
			}
			
		}			
						
	}else{
	//contours empty	
	}
	//imshow("color",color_frame);
	//imshow("pp",src_working);
	//waitKey(60000);

}

void resize_save(const char* folder){
	try{

	ostringstream  path;
	path << folder  << "1.png";
	string pth=path.str();	
	const char* file_n = pth.c_str();

	int final_w;
	int final_h;
	double bw = (double) src.cols;
	double bh = (double) src.rows;
	
	double temp_aspect_ratio = bw/bh;
	double scale_ratio_w=((double)device_screen_width)/bw;
	double scale_ratio_h=((double)device_screen_height)/bh;	/*
	__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "single, temp_aspect_ratio : %f" , temp_aspect_ratio);
	if(temp_aspect_ratio>=device_aspect_ratio){//fit width to screen, compute height 
		__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "single, device_aspect_ratio lover: %f" , device_aspect_ratio);

		final_w = device_screen_width;
		final_h=(int)cvRound(bh*scale_ratio_w);

		__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "resizing: width fits, final w %d , final h %d",final_w, final_h);
				
	}else{//fit height
		__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "single device_aspect_ratio higher: %f" , device_aspect_ratio);

		final_w=(int)cvRound(bw*scale_ratio_h);	
		final_h=device_screen_height;		
		
		__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "resizing: height fits, final w %d , final h %d",final_w, final_h);
	}	
	//cout <<"final_w " <<final_w  << endl;
	//cout <<"final_h " <<final_h  << endl;*/
	//Size new_size = Size(final_w,final_h);
	Size new_size = Size(device_screen_width,device_screen_height);

	equalize_levels(src);
	resize(src,src,new_size);
	////__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "resizing bg image, computed width %d, height %d", size.width, size.height);
	__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "saving %s", file_n);
	bool success = imwrite(file_n,src);
	
	//imwrite(file_o,original_size);
	if (success){
			java_notify();
	}
	}catch(std::exception& e){
				__android_log_print(ANDROID_LOG_DEBUG,"Native Part", "EXCEPTION IN NATIVE CODE: %s",e.what());
				return;
				//cout << "EXCEPTION" << e.what() << endl;
		
	}
}

vector<Point2f> sortCorners(vector<Point> corners_i){

	vector<Point2f> corners;
	vector<Point2f> top, bot;
	Point2f center;	
	vector<Point2f> result; 

	for (int i = 0; i < corners_i.size(); i++){
		Point2f point;
		point.x =(float)corners_i[i].x;
		point.y =(float)corners_i[i].y;		
		corners.push_back(point);
	}

	for (int i = 0; i < corners.size(); i++){
  		center += corners[i];
	}
	center *= (1. / corners.size());

	
	for (int i = 0; i < corners.size(); i++)
	{
		if (corners[i].y < center.y){
		top.push_back(corners[i]);}
		else{
		bot.push_back(corners[i]);}
	}

	Point tl = top[0].x > top[1].x ? top[1] : top[0];
	Point tr = top[0].x > top[1].x ? top[0] : top[1];
	Point bl = bot[0].x > bot[1].x ? bot[1] : bot[0];
	Point br = bot[0].x > bot[1].x ? bot[0] : bot[1];

	result.push_back(tl);
	result.push_back(tr);
	result.push_back(br);
	result.push_back(bl);
	
	return result;

}

bool sort_contours_rows( const vector<Point2f>  a, const vector<Point2f>  b ){

	  // if a's center is lower than b's bottom, order should be b a
	  if (((int)(a[0].y + a[3].y)/2 )> (int)b[3].y) {
	    return false;
	  }
	 // if b's center is lower than a's bottom, order should be a b
	  if ((int)a[3].y < (int)((b[0].y+b[3].y)/2)) {
	    return true; 
	  }
	 // they have overlap in y direction, just sort them by their x
         return (a[0].x< b[0].x);
    }

bool sort_contours_cols( const vector<Point2f>  a, const vector<Point2f>  b ){

	  // if a's center is further than b's right, order should be b a
	  if (((int)(a[2].x + a[3].x)/2 )> (int)b[2].x) {

	    return false;
	  }
	 // if b's center is further than a's right, order should be a b
	  if ((int)a[2].x < (int)((b[2].x+b[3].x)/2)) {

	    return true; 
	  }
	 // they have overlap in x direction, just sort them by their y
          return (a[2].y < b[2].y);
    }

bool sort_digits_rows( const vector<Point>  aa, const vector<Point>  bb ){
	  // get bounding boxes for a and b
	  Rect a = boundingRect(Mat(aa));
	  Rect b = boundingRect(Mat(bb));
	  // if a's center is lower than a's bottom, order should be b a
	  if (a.y+a.height/2 > b.y+b.height-1) {
	    return false;
	  }
	  // if b's center is lower than a's bottom, order should be a b
	  if (b.y+b.height/2 > a.y+a.height-1) {
	    return true; 
	  }
	  // they have overlap in y direction, just sort them by their x
	  return a.x < b.x;
}



Mat formatImageForPCA(Mat data)
{
	    Mat dst(1, data.rows*data.cols, CV_32F);
	    Mat image_row = data.clone().reshape(1,1);
	    image_row.convertTo(dst,CV_32F);
	    return dst;
}


void correct_orientation(){
	// correct portrait orientation to landscape
	  int temp_cols;
	  Size original_size = src.size();
	  original_rows = original_size.height;
	  original_cols = original_size.width;
	  
	if (original_rows>original_cols){
	  	//portrait = true;
	  	transpose(src, src);
		flip(src, src, 1);
		temp_cols = original_cols;
		original_cols = original_rows;
		original_rows = temp_cols;

	}

}

void remove_cyan_channel(){

		if (remove_cyan == false) { // gray-level image

			
		} else if (src.channels() == 3 ) { // color image
			int nl= src.rows; // number of lines
			int nc= src.cols * src.channels();// total number of elements per line
			int cyan = 0;
			int red = 0;
			int new_red = 0;
			for (int j=0; j<(nl-2); j++) {
				// get the address of row j
				uchar* original= src.ptr<uchar>(j);
				for (int i=0; i<nc; i++) {
					// process each pixel
					red=(int)original[3*i+2];//
					cyan=255-red;//cyan is the complement of red
					//TODO Possibly optimzation
					//tweak the cyan reduction here
					//cyan = cyan/4-50;
					cyan = cyan/16;		
					//cyan=0;
					new_red = 255-cyan;				

					if (new_red>=255){
						original[3*i+2]=255;
					}
					else if (new_red<0){	
						original[3*i+2]=0;
					}
					else {
						original[3*i+2]=(uchar)new_red;
					}

				}

			}
		cvtColor( src, src, CV_RGB2GRAY );
		}
	
	return;
}



void resize(){
	//resize for primary processing (finding sheet of paper )
//	  if (original_rows > v_res_primary){
	  if (original_rows != v_res_primary){
		
		//src_resized = true;
		// scale down presentation if image is too big (or too small)
		double original_rows_d=(double)original_rows;
		double v_res_primary_d=(double)v_res_primary;
		scale_ratio=cvRound(original_rows_d/v_res_primary_d);
		rows_scaled = original_rows/scale_ratio;        
		cols_scaled = original_cols/scale_ratio;
		rows_resized =  (int)cvRound(rows_scaled); 
		cols_resized =	(int)cvRound(cols_scaled);
		resize(src,src_working,Size(cols_resized,rows_resized), 0 , 0, CV_INTER_CUBIC );	

	  }
	  else if (original_rows == v_res_primary){
		src.copyTo(src_working);
		rows_resized = original_rows;
		cols_resized =original_cols;
	  }	
	 
}

/*
void negative(){
	int nl= src_working.rows; // number of lines
	int nc= src_working.cols;// total number of elements per line
	int new_grey;	
	for (int j=0; j<(nl); j++) {
		// get the address of row j
		uchar* original= src_working.ptr<uchar>(j);
		for (int i=0; i<nc; i++) {
			// process each pixel
			//TODO Possibly optimzation
			new_grey=255-original[i];//
			original[i]=(uchar)new_grey;
		}
	}
}
*/
void equalize_levels(Mat orig){
//TODO optimize
		double alpha = 1.4;
		int beta = 0;

	if (orig.channels() == 1) { // gray-level image
			Mat dst;
			Ptr<CLAHE> clahe = createCLAHE();
			clahe->setClipLimit(2);
			clahe->apply(orig,dst);
			orig=dst;
		
	} else if (orig.channels() == 3) { // color image


	
		  /// Separate the image in 3 places ( B, G and R )
		  vector<Mat> bgr_planes;
		  vector<Mat> bgr_result;
			Ptr<CLAHE> clahe = createCLAHE();
			clahe->setClipLimit(2);

		Mat bright;
		orig.copyTo(bright);
		for( int y = 0; y < orig.rows; y++ ){ 
			for( int x = 0; x < orig.cols; x++ ){
				 for( int c = 0; c < 3; c++ ){	

					 orig.at<Vec3b>(y,x)[c] = saturate_cast<uchar>( alpha*( bright.at<Vec3b>(y,x)[c] ) + beta );
				 }
			}
	     	}
			

		split( orig, bgr_planes );
		for (int i=0; i<bgr_planes.size(); i++) {
			Mat dst;
			
			clahe->apply(bgr_planes[i],dst);
			bgr_result.push_back(dst);

		}

		//Mat srcImg[] = {bgr_planes[0],bgr_planes[1],bgr_planes[2]};
			Mat srcImg[] = {bgr_result[0], bgr_result[1],bgr_result[2]};

		int from_to[] = { 0,0, 1,1, 2,2};
		mixChannels( srcImg, 3, &orig, 1, from_to, 3);	

	}



/*
	  /// Establish the number of bins
	  int histSize = 256;
	  /// Set the ranges ( for B,G,R) )
	  float range[] = { 0, 256 } ;
	  const float* histRange = { range };
	  bool uniform = true; bool accumulate = false;
	  Mat b_hist, g_hist, r_hist;
	  Mat b_result, g_result, r_result;

	  /// Compute the histograms:
	  calcHist( &bgr_planes[0], 1, 0, Mat(), b_hist, 1, &histSize, &histRange, uniform, accumulate );
	  calcHist( &bgr_planes[1], 1, 0, Mat(), g_hist, 1, &histSize, &histRange, uniform, accumulate );
	  calcHist( &bgr_planes[2], 1, 0, Mat(), r_hist, 1, &histSize, &histRange, uniform, accumulate );


	  //TODO convert to Ycbcr and normalize the lum channel , convert back
	  //TODO "white balance" as described here  http://docs.gimp.org/en/gimp-layer-white-balance.html

	float minValueR=0;
	float maxValueR=255;
	float minValueG=0;
	float maxValueG=255;
	float minValueB=0;
	float maxValueB=255;

	  //newValue = 255 * (oldValue - minValue)/(maxValue - minValue)
	if (orig.channels() == 1) { // gray-level image
		//TODO Process greyscale image;

	} else if (orig.channels() == 3) { // color image

		float red, green, blue;
		int nl= orig.rows; // number of lines
		// total number of elements per line
		int nc= orig.cols * orig.channels();
		for (int j=0; j<nl; j++) {
			// get the address of row j
			uchar* data= orig.ptr<uchar>(j);
			for (int i=0; i<nc; i++) {
	             		

				blue=cvRound(255 * (((float)data[3*i]) - minValueB)/(maxValueB - minValueB));
				green=cvRound(255 * (((float)data[3*i+1]) - minValueG)/(maxValueG - minValueG));
				red=cvRound(255 * (((float)data[3*i+2])- minValueR)/(maxValueR - minValueR));
				if (blue>=255){blue=255;}
				if (green>=255){green=255;}
				if (red>=255){red=255;}
				if (red<=0){red=0;}
				if (green<=0){green=0;}
				if (blue<=0){blue=0;}
				data[3*i]=(unsigned char)blue;
				data[3*i+1]=(unsigned char)green;
				data[3*i+2]=(unsigned char)red;

				data[3*i]=data[3*i];
				data[3*i+1]=data[3*i+1];
				data[3*i+2]=data[3*i+2];
			}

		}

	}
*/
}

Size compute_size(){
	int final_w;
	int final_h;
	double bw = avg_horizontal;
	double bh = avg_vertical;
//	int bh = avg_vertical;
	Size new_size;
	__android_log_print(ANDROID_LOG_DEBUG, "Native Part","avg_horizontal %f" ,avg_horizontal);
	__android_log_print(ANDROID_LOG_DEBUG, "Native Part","avg_vertical %f" ,avg_vertical);
	if(bw==0||bh==0){
		//__android_log_print(ANDROID_LOG_DEBUG, "Native Part"," wtf zero");
		new_size = Size(device_screen_width,device_screen_height);	
		return new_size;
	}	
	
	if(top_obscured||bottom_obscured){
		bh=avg_horizontal/forced_ratio;
	//	double scale_ratio_w=((double)device_screen_width)/bw;
	//	double scale_ratio_h=((double)device_screen_width)/bh;
	}
	//else{
		double scale_ratio_w=((double)device_screen_width)/bw;
		double scale_ratio_h=((double)device_screen_height)/bh;	
	//}
	//cout <<"scale_ratio_w " <<scale_ratio_w  << endl;
	//cout <<"scale_ratio_h " <<scale_ratio_h  << endl;
	//cout <<"device_aspect_ratio " << device_aspect_ratio << endl;
	//cout <<"average_aspect_ratio " <<average_aspect_ratio  << endl;
	//__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "bw %f", bw);
	//__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "bh %f", bh);
	
	if(average_aspect_ratio>=device_aspect_ratio){//fit width to screen, compute height 
	////cout <<" " <<  << endl;
	
		__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "device_aspect_ratio lover: %f" , device_aspect_ratio);

		final_w = device_screen_width;
		final_h=(int)cvRound(bh*scale_ratio_w);


		//final_h=(int)cvRound(bh*scale_ratio_w);
		//__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "resizing: width fits, final w %d , final h %d",final_w, final_h);
				
	}else{//fit height
		__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "device_aspect_ratio higher: %f" , device_aspect_ratio);

		final_w=(int)cvRound(bw*scale_ratio_h);	
		final_h=device_screen_height;		
		
		//__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "resizing: height fits, final w %d , final h %d",final_w, final_h);
	}	
	//cout <<"final_w " <<final_w  << endl;
	//cout <<"final_h " <<final_h  << endl;
	new_size = Size(final_w,final_h);
	return new_size;
}



bool the_hard_way(){//TODO WARNING CONSTRUCTION SITE 
	
	  Vec4i h_l;
	  Vec4i v_l;
	  vector<Vec4i> p_lines;
	  vector<Vec4i> long_lines;
	  vector<Vec4i> horizontal_lines;
	  vector<Vec4i> vertical_lines;

	  int x;
	  int y;
	  double angle = 0.;
	  double t_angle = 0.;

	  int min_line_lenght = (int)cvRound(src_working_copy.rows/24);//TODO tune 
	  int max_line_gap = (int)cvRound(src_working_copy.rows/150);


	int dilation_size2=1;
	Mat element3 = getStructuringElement( MORPH_ELLIPSE,
		                               Size( 2*dilation_size2 + 1, 2*dilation_size2+1 ),
		                               Point( dilation_size2, dilation_size2 ));
	( src_working_copy,src_working_copy, element3,cv::Point(-1,-1) );

	

	//imshow ("dilated permissive", thresholded2);
	//__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "global hough lines starting");
	//cout << "global hough lines starting" << endl;

	int rho = 1;//pixelresolution
	int votes = 50;
	imshow("src working copy",src_working_copy);	
	imshow("black frame",black_frame);	
	
	
	HoughLinesP( src_working_copy, p_lines, rho, CV_PI/180, votes, min_line_lenght, max_line_gap );

	//__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "global hough lines done");
	//cout << "global hough lines done, found " << p_lines.size() << "lines" <<  endl;

	unsigned nb_lines = p_lines.size();
        unsigned all_lines = nb_lines;
		
	
	for( size_t i = 0; i < p_lines.size(); i++ ){
	       Vec4i l = p_lines[i];
		if(interactive){
			//Scalar color = Scalar(rng.uniform(0, 255), rng.uniform(0, 255),rng.uniform(0, 255) );
		        //line( color_frame, Point(l[0], l[1]), Point(l[2], l[3]), color, 3, CV_AA);
		}
	       t_angle = atan2((double)p_lines[i][3] - p_lines[i][1],
		               (double)p_lines[i][2] - p_lines[i][0])  * 180 / CV_PI;
		double angle = abs(t_angle);


		if ((angle<7)||(angle>173)){        //horizontal

		Vec4i horizontal_line;
		//discard too long horizontal lines
		x=(l[0]-l[2]);
		y=(l[1]-l[3]);
			if(  (sqrt(x*x+y*y)) < src_working_copy.cols-30 ){
				horizontal_line[0]=(int)cvRound(l[0]/scale_ratio);
				horizontal_line[1]=(int)cvRound(l[1]/scale_ratio);
				horizontal_line[2]=(int)cvRound(l[2]/scale_ratio);
				horizontal_line[3]=(int)cvRound(l[3]/scale_ratio);
				horizontal_lines.push_back(horizontal_line);
				if(interactive){
					Scalar color = Scalar(0, rng.uniform(0, 255),0 );
	       				line( color_frame, Point(l[0], l[1]), Point(l[2], l[3]), color, 2, CV_AA);
				}
			}

        	}

 		else if ((angle<104)&&(angle>76)){ //near vertical
			Vec4i vertical_line;
			vertical_line[0]=(int)cvRound(l[0]/scale_ratio);
			vertical_line[1]=(int)cvRound(l[1]/scale_ratio);
			vertical_line[2]=(int)cvRound(l[2]/scale_ratio);
			vertical_line[3]=(int)cvRound(l[3]/scale_ratio);
		        vertical_lines.push_back(vertical_line);
				if(interactive){
					Scalar color = Scalar(0,0, rng.uniform(0, 255) );
					line( color_frame, Point(l[0], l[1]), Point(l[2], l[3]), color, 2, CV_AA);
				}
		}
	       else {
				if(interactive){
					Scalar color = Scalar( rng.uniform(0, 255), 0,0 );
					line( color_frame, Point(l[0], l[1]), Point(l[2], l[3]), color, 2, CV_AA);
				}
		}

	}
	//imshow ("pp", color_frame);
	
	Vec4i top;
	Vec4i right;
	Vec4i bottom;
	Vec4i left;
	if (!horizontal_lines.size()==0||vertical_lines.size()==0){
		Vec4i top =  horizontal_lines[0];
		Vec4i bottom = horizontal_lines[0];
		Vec4i left = vertical_lines[0];
		Vec4i right = vertical_lines[0];
	}else{
		return false;
	}
	vector<Vec4i> top_lines;
	vector<Vec4i> bottom_lines;
	vector<Vec4i> left_lines;
	vector<Vec4i> right_lines;

	cout << "horizontal_lines " << horizontal_lines.size()  << endl;
	cout << "vertical_lines " << vertical_lines.size()  << endl;

	//ESTABLISH EXTREME LINES
	  for( size_t i = 0; i < horizontal_lines.size(); i++ ){
		  h_l = horizontal_lines[i];

		if (h_l[1] > src_working_copy.rows/2){//bottom
			if  (h_l[1] > bottom[1]){
				bottom = h_l;
			}
		}
		else {//top lines
			if  (h_l[1] < top[1]){
				top = h_l;
			}
		}
	}

	for( size_t i = 0; i < vertical_lines.size(); i++ ){
		v_l = vertical_lines[i];
		if (v_l[0] > src_working_copy.cols/2){//right
			if  (v_l[0] > right[0]){
				right = v_l;
			}
		}
		else {//left lines
			if  (v_l[0] < left[0]){
				left = v_l;
			}
		}

  	}

	double n_left=0;
	double n_right=0;
	double n_top=0;
	double n_bottom=0;

	double temp_angle=0;
	double angle_left=0;
	double angle_right=0;
	double angle_top=0;
	double angle_bottom=0;


	double x_left=0;
	double x_right=0;
	double x_top=0;
	double x_bottom=0;

	double y_left=0;
	double y_right=0;
	double y_top=0;
	double y_bottom=0;


	double x2_left=0;
	double x2_right=0;
	double x2_top=0;
	double x2_bottom=0;

	double y2_left=0;
	double y2_right=0;
	double y2_top=0;
	double y2_bottom=0;

	int tolerance = 10;
	for( size_t i = 0; i < horizontal_lines.size(); i++ ){
		  h_l = horizontal_lines[i];

		if ((abs(h_l[1] - top[1])<tolerance)||(abs(h_l[3] - top[3])<tolerance)){//top
	 	     top_lines.push_back(h_l);
		     n_top++;
		     x_top += h_l[0];
		     y_top += h_l[1];
		     x2_top += h_l[2];
		     y2_top += h_l[3];
		       temp_angle = atan2((double)h_l[3] - h_l[1],
		                       (double)h_l[2] - h_l[0])  * 180 / CV_PI;
			angle_top += temp_angle;
	//cout <<  "top " << temp_angle  <<endl;
		      // Scalar color = Scalar(rng.uniform(0,255), rng.uniform(0,255),0 );
            	      // line( color_frame, Point(h_l[0], h_l[1]), Point(h_l[2], h_l[3]), color, 2, CV_AA);

		}

		if ( (abs(h_l[1] - bottom[1])<tolerance)||(abs(h_l[3] - bottom[3]) <tolerance) ){//bottom
		     bottom_lines.push_back(h_l);
		     n_bottom++;
		     x_bottom += h_l[0];
		     y_bottom += h_l[1];
		     x2_bottom += h_l[2];
		     y2_bottom += h_l[3];
		       temp_angle = atan2((double)h_l[3] - h_l[1],
		                       (double)h_l[2] - h_l[0])  * 180 / CV_PI;
			angle_bottom += temp_angle;
	//cout <<  "bottom " << temp_angle <<endl;
			if(interactive){
			       Scalar color = Scalar(0,0, rng.uniform(0,255));
			       line( color_frame, Point(h_l[0], h_l[1]), Point(h_l[2], h_l[3]), color, 2, CV_AA);
			}

		}

	}

 	for( size_t i = 0; i < vertical_lines.size(); i++ ){
		v_l = vertical_lines[i];
		if ( (abs(v_l[0] - right[0])<tolerance) || (abs(v_l[2] - right[2])<tolerance) ){//right
		     right_lines.push_back(v_l);
		     n_right++;
		     x_right += v_l[0];
		     y_right += v_l[1];
		     x2_right += v_l[2];
		     y2_right += v_l[3];
		       temp_angle = atan2((double)v_l[3] - v_l[1],
		                       (double)v_l[2] - v_l[0])  * 180 / CV_PI;
	//cout <<  "T right " << temp_angle <<endl;
			if(temp_angle<0){
			temp_angle +=90;
			angle_right += temp_angle;}
			else {
			temp_angle -=90;
			angle_right += temp_angle;
			}
	//cout <<  "corrected to T right " << temp_angle <<endl;
			if(interactive){
		       		Scalar color = Scalar( rng.uniform(0, 255), 0, rng.uniform(0,255) );
		       		line( color_frame, Point(v_l[0], v_l[1]), Point(v_l[2], v_l[3]), color, 2, CV_AA);
			}	
		}
		if ((abs(v_l[0] - left[0])<tolerance)||(abs(v_l[2] - left[2])<tolerance)){
		     left_lines.push_back(v_l);
	             n_left++;
		     x_left += v_l[0];
		     y_left += v_l[1];
		     x2_left += v_l[2];
		     y2_left += v_l[3];
		       temp_angle = atan2((double)v_l[3] - v_l[1],
		                       (double)v_l[2] - v_l[0])  * 180 / CV_PI;

	//cout <<  "T left" << temp_angle <<endl;
			if(temp_angle<0){
			temp_angle +=90;
			angle_left += temp_angle;}
			else {
			temp_angle -=90;
			angle_left += temp_angle;
			}
	//cout <<  "corrected to T left " << temp_angle <<endl;
			if(interactive){
			       Scalar color = Scalar( 0, rng.uniform(0,255), rng.uniform(0,255) );
			       line( color_frame, Point(v_l[0], v_l[1]), Point(v_l[2], v_l[3]), color, 2, CV_AA);
			}
		}
		temp_angle=0;

	}
	
	//averaghe the lines
	if ((n_top==0) || (n_bottom==0) || (n_left ==0) || (n_right==0)){
	return false;
	}

	int average_y_top=(int)cvRound(( y_top/n_top + y2_top/n_top )/2);
	int average_y_bottom=(int)cvRound((y_bottom/n_bottom + y2_bottom/n_bottom )/2);
	int average_x_right=(int)cvRound((x_right/n_right + x2_right/n_right)/2);
	int average_x_left=(int)cvRound((x_left/n_left + x2_left/n_left)/2);

	double average_a_top=(angle_top/n_top);
	double average_a_bottom=(angle_bottom/n_bottom);


	double average_a_right;
	average_a_right=(angle_right/n_right)+90;

	double average_a_left;
	average_a_left=(angle_left/n_left)+90;

	 /*
	cout <<  "top " << average_a_top <<endl;
	cout <<  "bottom " << average_a_bottom <<endl;
	cout <<  "right " << average_a_right <<endl;
	cout <<  "left " << average_a_left <<endl;
	*/

	int arbitrary_x_top = 50;
	int arbitrary_y_left= 50;
	double ar = 400;  //arbitrary lenght

	int computed_x_top;
	int computed_x_bottom;
	int computed_x_right;
	int computed_x_left;

	int computed_y_top;
	int computed_y_bottom;
	int computed_y_right;
	int computed_y_left;


	computed_y_top=(int)cvRound( average_y_top+ sin(average_a_top*CV_PI/180)*ar);
	computed_y_bottom=(int)cvRound(average_y_bottom+ sin(average_a_bottom*CV_PI/180)*ar);

	computed_x_right=(int)cvRound(average_x_right+ cos(average_a_right*CV_PI/180)*ar);
	computed_x_left=(int)cvRound(average_x_left+ cos(average_a_left*CV_PI/180)*ar);
	/*
	cout <<  "right COSINE " << cos(average_a_right) <<endl;
	cout <<  "left COSINE " << cos(average_a_left) <<endl;
	*/
	Vec4i top_line = Vec4i( arbitrary_x_top, average_y_top,src_working_copy.cols-arbitrary_x_top, computed_y_top);
	Vec4i bottom_line = Vec4i(arbitrary_x_top, average_y_bottom,src_working_copy.cols-arbitrary_x_top, computed_y_bottom);
	Vec4i right_line = Vec4i( average_x_right ,arbitrary_y_left, computed_x_right, src_working_copy.rows - arbitrary_y_left);
	Vec4i left_line =Vec4i( average_x_left ,arbitrary_y_left, computed_x_left, src_working_copy.rows - arbitrary_y_left);

	/*
	cout <<  "top " << top_line <<endl;
	cout <<  "bottom " << bottom_line <<endl;
	cout <<  "right " << right_line <<endl;
	cout <<  "left " << left_line <<endl;
*/
	if (interactive){
       line( color_frame, Point(top_line[0], top_line[1]), Point(top_line[2], top_line[3]), Scalar(255,255,255), 2, CV_AA);
       line( color_frame, Point(bottom_line[0], bottom_line[1]), Point(bottom_line[2], bottom_line[3]), Scalar(255,255,255), 2, CV_AA);
       line( color_frame, Point(right_line[0], right_line[1]), Point(right_line[2], right_line[3]), Scalar(255,255,255), 2, CV_AA);
       line( color_frame, Point(left_line[0], left_line[1]), Point(left_line[2], left_line[3]), Scalar(255,255,255), 2, CV_AA);
	}

	Point2f tl;
	Point2f tr;
	Point2f bl;
	Point2f br;
	tl =  computeIntersect(top_line, left_line);
	tr =   computeIntersect(top_line, right_line);
	bl =  computeIntersect(bottom_line, left_line);
	br =   computeIntersect(bottom_line, right_line);
	if (interactive){
       line( color_frame, Point(tr.x, tr.y), Point(tl.x, tl.y), Scalar(255,255,255), 1, CV_AA);
       line( color_frame, Point(br.x, br.y), Point(bl.x, bl.y), Scalar(255,255,255), 1, CV_AA);
       line( color_frame, Point(tr.x, tr.y), Point(br.x, br.y), Scalar(255,255,255), 1, CV_AA);
       line( color_frame, Point(tl.x, tl.y), Point(bl.x, bl.y), Scalar(255,255,255), 1, CV_AA);
	}

	
	//check if intersection points are within image
	if ((tl.x < 0)||(tr.x < 0)||(bl.x < 0)||(br.x < 0)||(tl.y < 0)||(tr.y < 0)||(bl.y< 0)||(br.y < 0)){
		//__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "picture outside of range ");
	return false;
	}
	if ((tl.y > src_working_copy.rows)||(tr.y > src_working_copy.rows)||(bl.y > src_working_copy.rows)||(br.y > src_working_copy.rows)||
		(tl.x  > src_working_copy.cols)||(tr.x  > src_working_copy.cols)||(bl.x  > src_working_copy.cols)||(br.x  > src_working_copy.cols)){
		//__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "picture outside of range ");
	return false;
	}

	


	//check line lenght similarity and angles symmetry
	int top_x=(tl.x-tr.x);
	int top_y=(tl.y-tr.y);
	int top_lenght =(int)sqrt(top_x^2+top_y^2);

	int bottom_x=(bl.x-br.x);
	int bottom_y=(bl.y-br.y);
	int bottom_lenght =(int)sqrt(bottom_x^2+bottom_y^2);

	int left_x=(tl.x-bl.x);
	int left_y=(tl.y-bl.y);
	int left_lenght =(int)sqrt(left_x^2+left_y^2);

	int right_x=(tr.x-br.x);
	int right_y=(tr.y-br.y);
	int right_lenght =(int)sqrt(right_x^2+right_y^2);

	//TODO find right parameters for difference
	int allowed_h = src_working_copy.cols/3;
	int allowed_v = src_working_copy.rows/3;

	if (abs(top_lenght-bottom_lenght)>allowed_h){
		if (abs(left_lenght-right_lenght)>allowed_v){
		//__android_log_print(ANDROID_LOG_DEBUG, "Native Part", "line lenghts are different");
		return false;
		}
	}

	vector<Point2f> paper_corners;

	paper_corners.clear();   
	paper_corners.push_back(tl);
	paper_corners.push_back(tr);
	paper_corners.push_back(br);
	paper_corners.push_back(bl);
	outside_corners.push_back(paper_corners);

	//cout<<"paper pts " << paper_corners << endl;
	//imshow ("outlines",color_frame);
	//waitKey(60000);
	return true;
}

Point computeIntersect(cv::Vec4i a, cv::Vec4i b){
	int x1 = a[0], y1 = a[1], x2 = a[2], y2 = a[3], x3 = b[0], y3 = b[1], x4 = b[2], y4 = b[3];
	float denom;

	if (float d = ((float)(x1 - x2) * (y3 - y4)) - ((y1 - y2) * (x3 - x4)))
	{
		cv::Point2f pt;
		pt.x = ((x1 * y2 - y1 * x2) * (x3 - x4) - (x1 - x2) * (x3 * y4 - y3 * x4)) / d;
		pt.y = ((x1 * y2 - y1 * x2) * (y3 - y4) - (y1 - y2) * (x3 * y4 - y3 * x4)) / d;
		return pt;
	}
	else{
	return cv::Point2f(-1, -1);}
}



////////////////////////////////////////////////////////////