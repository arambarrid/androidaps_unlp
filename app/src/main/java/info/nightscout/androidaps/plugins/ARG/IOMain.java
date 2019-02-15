/// ********************************************************************************************************************
//  Copyright 2011-2013 by the University of Virginia
//	All Rights Reserved
//
//  Created by Patrick Keith-Hynes
//  Center for Diabetes Technology
//  University of Virginia
/// ********************************************************************************************************************
package info.nightscout.androidaps.plugins.ARG;

// ************************************************************************************************************ //

import android.content.ContentValues;
import android.content.Context;
import android.app.Service;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.Message;
import android.os.Handler;
import android.os.RemoteException;
import android.widget.Toast;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;

import android.app.Notification;
import android.app.PendingIntent;

import android.content.res.Resources;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.Objects;

import org.json.JSONObject;
import org.json.JSONException;

import info.nightscout.androidaps.Constants;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.ConfigBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.NSClientInternal.data.NSSgv;
import info.nightscout.androidaps.plugins.Overview.OverviewPlugin;
import info.nightscout.utils.DecimalFormatter;
import info.nightscout.utils.SP;


// ************************************************************************************************************ //

public class IOMain{


    private static Logger log = LoggerFactory.getLogger(L.DATABASE);

	
	// Power management
	/*

	private PowerManager pm;
	private PowerManager.WakeLock wl;
	
	private int hypoLight  = 0;
	private int hyperLight = 0;
	
	private static final String TAG = "HMSservice";
	
    private Messenger mMessengerToClient = null;
    private final Messenger mMessengerFromClient = new Messenger(new IncomingHMSHandler());
    */

	public void ejecutarCada5Min(GController gController) {
		// Debug
		log.debug("ARG /////// "+"APC_SERVICE_CMD_CALCULATE_STATE start");
		
		/*
		
		//

		Bundle paramBundle = msg.getData();
		boolean asynchronous = paramBundle.getBoolean("asynchronous");		//Flag to indicate synchronous (every 5 minutes) or asynchronous (meal entry)
		int DIAS_STATE = paramBundle.getInt("DIAS_STATE", 0);				//Current DiAs State at time of call

		// Log the parameters for IO testing
		if (Params.getBoolean(getContentResolver(), "enableIO", false)) {
			Bundle b = new Bundle();
			b.putString(	"description", 
							" SRC: DIAS_SERVICE"+
							" DEST: APC"+
							" -"+FUNC_TAG+"-"+
							" APC_SERVICE_CMD_CALCULATE_STATE"+
							" Async: "+asynchronous+
							" DIAS_STATE: "+DIAS_STATE
						);
			Event.addEvent(getApplicationContext(), Event.EVENT_SYSTEM_IO_TEST, Event.makeJsonString(b), Event.SET_LOG);
		}
		
		double correction = 0.0, diff_rate = 0.0;
		boolean new_rate = false;
		
		/************************************************************************************************************* //
		
		// Esto se ejecuta con el DiAs en cualquier modo
		
		if (DIAS_STATE == State.DIAS_STATE_CLOSED_LOOP || DIAS_STATE == State.DIAS_STATE_OPEN_LOOP || DIAS_STATE == State.DIAS_STATE_STOPPED || DIAS_STATE == State.DIAS_STATE_SENSOR_ONLY) {
			
			// Only run this in synchronous mode (asynchronous is for meals)
			
			// Debug
			
			log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS: New iteration");
			
			//
			
			if (!asynchronous) {
				
				// Debug
	    		
	    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS: Synchronous call");
	    		
	    		//
				
				//This is an example call that will read all the subject data from the DB
				//You don't have to call this here, this is just how to call it and fill the fields
	    		
				Subject subject = new Subject();
			
				if(subject.read(getApplicationContext())) {
					
					//Outputting in this loop ensures that you have all the insulin profile data you need
					
					//The values CF, CR, and Basal are the current values from the time you call "read()" (as shown above)
					/// *********************************************************
					//Probably best to insert your calculation routine here...
					
					gController.getPatient().setCf(subject.CF);
					gController.getPatient().setCr(subject.CR);
					gController.getPatient().setTdi(subject.TDI);
					gController.getPatient().setWeight(subject.weight);
					gController.setSetpoint(subject.age); // age = setpoint
					gController.getPatient().setBasalU(subject.basal);
		    			
					double parameterIOBFactor = subject.height;
					
					// Debug
		    		
					double parameterIOBFactorF = new BigDecimal(Double.toString(0.015957446808511*parameterIOBFactor-1.776595744680853)).setScale(2, RoundingMode.HALF_DOWN).doubleValue();
					
		    		log.debug("ARG /////// "+"IOB Factor: "+parameterIOBFactorF);
		    		
		    		//
					
					// ************************************************************************************************************ //
					// ************************************************************************************************************ //
					
					// Rutina para capturar bolos asincrónicos para actualizar el IOB
		    		
					// *********************************************************************************************************** /// 
					
		    		double  delTotal     = 0.0;   // Variable para capturar cada uno de los posibles bolos asincrónicos
		    		int     statusIns    = 0;     // Variable que permite detectar si el bolo es el anunciado en la inicialización o fue dado con el DiAs 
		    		int     type         = 0;     // Variable equivalente a statusIns
		    		long    lastTime     = 0;     // Tiempo de la infusión del bolo
		    		long    currentTime  = System.currentTimeMillis()/1000; // Tiempo actual en segundos
		    		double  extraBolus   = 0.0;   // Bolos de corrección o de comida
		        	double  iobInitBolus = 0.0;   // Bolo de inicialización
		        	long    timeDiff     = 0;     // Diferencia entre el tiempo actual y el de la infusión
		        	boolean iobInitFlag  = false; // Flag para indicar que se debe ejecutar la rutina de inicialización de IOB
		        	
		        	// Puntero a la tabla de insulina. Capturo las filas cuyo tiempo se del actual hasta 5 min anteriores
		        	// Consulto la columna deliv_time para asegurarme que el bolo fue infundido
		        	
		    		Cursor aTime = getContentResolver().query(Biometrics.INSULIN_URI,null,
		    		         new String("deliv_time") + "> ?", new String[]{Long.toString(currentTime-299)}, null);
		    		
		    		if (aTime != null) {
			    		while(aTime.moveToNext()){
			    			
			    			lastTime  = aTime.getLong(aTime.getColumnIndex("deliv_time"));
				        	delTotal  = aTime.getDouble(aTime.getColumnIndex("deliv_total"));
				        	statusIns = aTime.getInt(aTime.getColumnIndex("status"));
				        	type      = aTime.getInt(aTime.getColumnIndex("type"));
				        	
				        	if(Objects.equals(lastTime, null)){
				        		
				        		// Si son nulls los seteo en 0
				        		
		        				lastTime  = 0;
		        				delTotal  = 0.0;
		        				statusIns = 0;
		        				type      = 0;
		        				
		        				// Debug
		        				
		        				log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Captura de bolos asincrónicos. Last insulin deliv time null! --> Ins deliv time = 0");
		        				
		        				//
		        				
				        	}else{
				        		
					        	if(type==3){ // type==3 indica que el bolo fue de inicialización
					        		
					        		iobInitBolus = delTotal; // If there is more than one stop-open or stop-closed transition during the last 5 minutes, I get the last amount of insulin that was MANUALLY injected.
					        		iobInitFlag  = true; // Activo el flag que dispara la rutina de inicialización
					        		
					        	}
					        	else if(type==2){ // type==2 indica que el bolo fue de corrección
					        		
					        		extraBolus += delTotal; // I accumulate all the insulin boluses that were injected during the last 5 minutes.
					        		
					        	}
					        	
					        	// Debug
					        	
					        	log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Captura de bolos asincrónicos. lastTime: " + lastTime + ". delTotal: " + delTotal + ". statusIns: " + statusIns + ". type: " + type);
					        	
					        	//
					        	
				        	}
				        	
			    		}
			    		
		    		}
		    		
		    		else{
	    				
		        		// Debug
			    		
			    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Captura de bolos asincrónicos. No asynchronous insulin boluses were detected! --> Ins deliv time = 0");
			    		
			    		//
			    		
		        	}
		    		
		    		aTime.close();
		    		
		    		// Debug
		    		
		    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Captura de bolos asincrónicos. extraBolus: " + extraBolus + ". iobInitBolus: " + iobInitBolus);
		    		
		    		//
		    		
		    		// *********************************************************************************************************** /// 
		    		// ************************************************************************************************************ //

		        	// Rutina corrección IOB por bolos sincrónicos no infundidos
		    		
		    		// Debug
		    		
		    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Corrección IOB. "
		    				+ "Inicio");
		    		
		    		//
		    		
		    		// Puntero al último bolo de insulina sincrónico
		    		
		    		Cursor sBTime = getContentResolver().query(Biometrics.INSULIN_URI,null,
		    		         "req_time>? AND type=?" , new String[]{Long.toString(currentTime-305),"1"}, null);
		    		
		    		
		    		if (sBTime != null) {
			    		if (sBTime.moveToLast()){
			    			
			    			// Capturo el estado
			    			
			    			statusIns = sBTime.getInt(sBTime.getColumnIndex("status"));
			    			
			    			// Debug
				    		
				    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Corrección IOB. "
				    				+ "statusIns: "+statusIns);
				    		
				    		//
				    		
			    			// Chequeo si no se infundió
			    			if(statusIns!=2){
			    				
			    				// Puntero a la tabla de IOB
			    				Cursor cIob = getContentResolver().query(Biometrics.USER_TABLE_1_URI, null, null, null, null);
			    				
			    				if (cIob != null) {
	    			        		
			    					// Voy a la última fila
	    			        		if (cIob.moveToLast()) {
			    			        	
	    			        			// Capturo los elementos de la última fila
			    			        	long iobLastTime = cIob.getLong(cIob.getColumnIndex("time"));
	    					        	double iobState1 = cIob.getDouble(cIob.getColumnIndex("d0"));
	    					        	double iobState2 = cIob.getDouble(cIob.getColumnIndex("d1"));
	    					        	double iobState3 = cIob.getDouble(cIob.getColumnIndex("d2"));
	    					        	double iobEst    = cIob.getDouble(cIob.getColumnIndex("d3"));
	    					        	double iobBasal  = cIob.getDouble(cIob.getColumnIndex("d4"));
	    					        	
	    					        	// Debug
							    		
							    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Corrección IOB. "
							    				+ "Estados últimos. iobLastTime: "+iobLastTime+
							    				". iobState1: "+iobState1+". iobState2: "+iobState2+
							    				". iobState3: "+iobState3+". iobBasal: "+iobBasal+
							    				"iobEst: "+iobEst);
			    			    		
							    		//
							    		
	    					        	// Voy a la anteúltima fila
	    			        			if(cIob.moveToPrevious()){
	    			        				
	    			        				// Capturo los elementos de la penúltima fila
		    					        	iobState1   = cIob.getDouble(cIob.getColumnIndex("d0"));
		    					        	iobState2   = cIob.getDouble(cIob.getColumnIndex("d1"));
		    					        	iobState3   = cIob.getDouble(cIob.getColumnIndex("d2"));
		    					        	iobBasal    = cIob.getDouble(cIob.getColumnIndex("d4"));
		    					        	
		    					        	// Debug
								    		
								    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Corrección IOB. "
								    				+ "Estados penúltimos. iobLastTime: "+iobLastTime+
								    				". iobState1: "+iobState1+". iobState2: "+iobState2+
								    				". iobState3: "+iobState3+". iobBasal: "+iobBasal);
				    			    		
								    		//
								    		
		    					        	// Seteo como estado inicial el anterior al último que fue incorrectamente
		    					        	// actualizado
		    					        	
		    					        	double[][] xTemp = {{iobState1},{iobState2},{iobState3}};
		        			    			Matrix iobState  = new Matrix(xTemp);
		    	    			    		gController.getSafe().getIob().setX(iobState);
		    	    			    		
		    	    			    		// Actualizo el IOB considerando que no se infundió nada
		    	    			    		
		    	    			    		double[][] uTemp = {{0.0}};
			    			    			Matrix u = new Matrix(uTemp);
			    			    			
				    			    		for(int jj = 0; jj < (currentTime-iobLastTime)/60.0/gController.getSafe().getTs(); ++jj){ 
				    			    			
				    			    			gController.getSafe().getIob().stateUpdate(u);
			    			    			
				    			    		}
				    			    		
				    			    		// Recalculo el iobEst
				    			    		iobEst   = gController.getSafe().getIobEst(gController.getPatient().getWeight());
				    			    		
				    			    		// Guardo los estados de IOB corregidos
				    			    		ContentValues statesTableIOB = new ContentValues();
			        			    		TableShortCut scTableIOB = new TableShortCut(); 
			        			    		double[][] iobStates = gController.getSafe().getIob().getX().getData();
			        			    		statesTableIOB = scTableIOB.insertValue(statesTableIOB, iobStates[0][0], iobStates[1][0], iobStates[2][0], iobEst, 
			        			    				iobBasal, 0.0, 0.0, 0.0, 0.0, 
			        			    				0.0, 0.0, 0.0, 0.0);
			        			    		statesTableIOB.put("time", iobLastTime);
			        						getContentResolver().insert(Biometrics.USER_TABLE_1_URI, statesTableIOB);
				    			    		
			        						// Debug
								    		
								    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Corrección IOB. "
								    				+ "Estados corregidos. iobLastTime: "+iobLastTime+
								    				". iobState1: "+iobStates[0][0]+". iobState2: "+iobStates[1][0]+
								    				". iobState3: "+iobStates[2][0]+". iobBasal: "+iobBasal+
								    				"iobEst: "+iobEst);
				    			    		
								    		//
								    		
	    			        			}
	    			        			
	    			        			else{
		    			        			
	    			        				// Si no hay anteúltima fila (muy poco probable), significa que los estados
	    			        				// antepenúltimos eran 0. Por lo tanto, grabo 0.
	    			        				
	    			        				ContentValues statesTableIOB = new ContentValues();
			        			    		TableShortCut scTableIOB = new TableShortCut(); 
			        			    		statesTableIOB = scTableIOB.insertValue(statesTableIOB, 0.0, 0.0, 0.0, 0.0, 
			        			    				iobBasal, 0.0, 0.0, 0.0, 0.0, 
			        			    				0.0, 0.0, 0.0, 0.0);
			        			    		statesTableIOB.put("time", iobLastTime);
			        						getContentResolver().insert(Biometrics.USER_TABLE_1_URI, statesTableIOB);
			        						
			        						// Debug
								    		
								    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Corrección IOB. "
								    				+ "No había estados penúltimos --> IOB = 0");
				    			    		
								    		//
								    		
	    			        			}
	    			        			
	    			        		}
	    			        		
			    				}
			    				
			    			}
			    			
			    			else{
			    				
			    				// Debug
					    		
					    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Corrección IOB. "
					    				+ "El bolo se infundió correctamente");
	    			    		
					    		//
			    				
			    			}
			    			
			    		}
			    		
			    		else{
			    			
			    			// Debug
				    		
				    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Corrección IOB. "
				    				+ "sBTime.moveToLast() = false");
				    		
				    		//
			    			
			    		}
			    	
		    		}
		    		
		    		else{
		    			
		    			// Debug
			    		
			    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Corrección IOB. "
			    				+ "sBTime = null");
			    		
			    		//
		    			
		    		}
		    		
		    		sBTime.close();
		    		
		        	// ************************************************************************************************************ //
		    		// ************************************************************************************************************ //

		        	// Rutina captura de estados del modelo de IOB
		    		
		    		// ************************************************************************************************************ //
		    		
		    		// Debug
		    		
		    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Captura estados IOB. Iob Initialization");
		    		
		    		//
		    		
		        	long   iobLastTime = 0;
		        	double iobState1   = 0.0;
		        	double iobState2   = 0.0;
		        	double iobState3   = 0.0;
		        	
		        	// Puntero a la tabla de IOB
		        	
		    		Cursor cIob = getContentResolver().query(Biometrics.USER_TABLE_1_URI, null, null, null, null);
		    		
		    		// Cuando se prende el smartphone luego de mucho tiempo el moveToLast devuelve null por más que haya registros
		    		// en la tabla. Esto origina que iobLastTime = 0, al igual que los estados. No es un problema, ya que de esta forma 
		    		// se comienza el proceso de inicialización con los estados en 0, que es lo que adecuado por la diferencia temporal
		    		// que existe
		    		
		        	if (cIob != null) {
		        		
		        		if (cIob.moveToLast()) {
		        			
		        			iobLastTime = cIob.getLong(cIob.getColumnIndex("time"));
				        	iobState1   = cIob.getDouble(cIob.getColumnIndex("d0"));
				        	iobState2   = cIob.getDouble(cIob.getColumnIndex("d1"));
				        	iobState3   = cIob.getDouble(cIob.getColumnIndex("d2"));

		        			if(Objects.equals(iobLastTime, null)){
		        				
		        				iobLastTime = 0;
		        				iobState1   = 0.0;
		        				iobState2   = 0.0;
		        				iobState3   = 0.0;
		        				
		        				// Debug
	    			    		
	    			    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Captura estados IOB. Iob last time null! --> Iob = 0");
	    			    		
	    			    		//
	    			    		
		        			}
		        			
		        		}
		        		
		        		else{
	        				
	        				// Debug
				    		
				    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Captura estados IOB. Iob table empty! --> Iob = 0");
				    		
				    		//	
				    		
		        		}
		        		
		        	}
		        	
		        	else{	
		        		
		        		// Debug
			    		
			    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Captura estados IOB. Error loading iob table! --> Iob = 0");
			    		
			    		//
			    		
			    		Toast.makeText(IOMain.this, "Error loading iob table!" , Toast.LENGTH_SHORT).show();
			    	
		        	} 
		        	
		        	cIob.close();
		        	
		        	// Debug
		        	
		        	log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Captura estados IOB. Estados capturados --> iobState1: " + iobState1 + ". iobState2: " + iobState2 + ". iobState3: " + iobState3);
		        	
		        	//
		        	
		        	//double[][] xTemp = {{0.0},{0.0},{0.0}};
		        	
		        	// Seteo los estados del IOB capturados
		        	
		        	double[][] xTemp = {{iobState1},{iobState2},{iobState3}};
	    			Matrix iobState  = new Matrix(xTemp);
		    		gController.getSafe().getIob().setX(iobState);
		    		
		    		// ************************************************************************************************************ //
		    		// ************************************************************************************************************ //
		    		
		    		// Rutina inicialización de IOB
		    		
		    		// ************************************************************************************************************ //
		    		// Manejo bolo de inicialización
		    		
		        	if(iobInitFlag){
		        		
		        		// En el DiAs se guarda un vector de insulina basal con el valor de insulina basal en U/h y el tiempo del día en minutos
		        		
			        	timeDiff = currentTime - iobLastTime; // Diferencia temporal entre el tiempo actual y el tiempo en que se guardó
			        										  // la última actualización de IOB
			        	
			    		// Get the offset in seconds into the current day in the current time zone (based on cell phone time zone setting)
			    		TimeZone tz         = TimeZone.getDefault();
			    		int UTC_offset_secs = tz.getOffset(currentTime*1000)/1000; // El tiempo está en UTC, tengo que convertirlo al tiempo en Bs As
			    																   // que es -3hs. Acá capturo el offset que sería de -10800 seg
			    		int timeNowSecs     = (int)(currentTime+UTC_offset_secs)%(1440*60); // El tiempo actual lo convierto a segundos entre 0-24 hs
			    		int timeIobSecs     = (int)(iobLastTime+UTC_offset_secs)%(1440*60); // El tiempo de IOB lo convierto a segundos entre 0-24 hs
			    		
			    		boolean flagAdd = true; // Uso este flag para indicar si el primer elemento del vector de entrada que genero a partir de 
			    								// la insulina basal tengo que agregarle el índice anterior o no
			    		
			    		// Debug
			    		
			    		log.debug( "ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. Now [sec]: "+timeNowSecs+". UTC_offset_secs: "+UTC_offset_secs+ ". IOB time [min]: " + timeIobSecs/60 + ". Now [min]: " + timeNowSecs/60 + ". timeDiff [sec]: "+timeDiff);
			    		
			    		//
			    		
			    		// A continuación voy a definir un vector de insulina basal, tiempo para usar como entrada en el modelo de IOB. Para eso defino un vector de índices que indicarán
			    		// los instantes en que la insulina basal cambia.
			    		
			    		// En el DiAs la insulina basal se define como un tiempo a partir del cual se aplica y el valor en U/h.
			    		
			    		// El vector de índices capturará esos tiempos, y es por eso importante destacar que son instantes de cambio.
			    		
			    		List<Integer> indices  = new ArrayList<Integer>(); // Vector de índices para definir la entrada al modelo de IOB
			    		List<Integer> indices1 = new ArrayList<Integer>(); // Parte 1 del vector
			    		List<Integer> indices2 = new ArrayList<Integer>(); // Parte 2 del vector
			    		
			    		List<Pair> iobInput = new ArrayList<Pair>(); // Defino una lista de pares. 
			    													 // Cada par representa: 
			    													 // la velocidad de infusión y el tiempo que se aplica
			    		
			    		// Get the latest subject data and profiles from biometricsContentProvider
			    		DiAsSubjectData subject_data;
			    		if ((subject_data = DiAsSubjectData.readDiAsSubjectData(getApplicationContext())) == null) {
			    			
			    			// Debug
			    			
			    			log.error("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS: Subject database failed to be read...");
			    			
			    			//
			    			
			    			Toast.makeText(IOMain.this, "Error reading subject information!" , Toast.LENGTH_SHORT).show();
			    			
			    		}
			    			    			    		
			    		// Get basal values
			    		List<Integer> indicesAux  = new ArrayList<Integer>();
	    				indicesAux = subject_data.subjectBasal.find(">", -1, "<", -1); // Cargo todos los índices del vector de insulina basal
	    				
	    				long t0 = 0;
	    				long tf = timeNowSecs/60; // Paso segundos a minutos que es cómo está informado en el DiAs
	    				
	    				//iobLastTime = 0;
	    				
	    				// Si la diferencia temporal es mayor a 4 hs, entonces tengo que buscar la insulina basal de las últimas 4 hs
	    			
			    		if(timeDiff>14405){ // I added 5 s to take into account minimum differences in time synchronization
			    			
			    			log.debug( "ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. currentTime-iobLastTime>14405");
			    			
			    			// Si la hora actual es mayor que las 04 h, entonces busco los índices 4 hs hacia atrás
			    			
			    			if(timeNowSecs/60>=240){
			    				
			    				log.debug( "ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. currentTime-iobLastTime>14405. IF");
			    				
			    				indices1 = subject_data.subjectBasal.find(">", timeNowSecs/60-240, "<=", timeNowSecs/60);
			    				t0 = timeNowSecs/60-240;
			    				
			    			}
			    			
			    			// Sino, tengo que considerar que 1440 min (24 h) y 0 min (0 h) es el mismo punto temporal en el vector de insulina basal.
			    			// Por ende, si por ejemplo el tiempo actual es 03 hs, entonces busco entre las 00 y las 03, y luego entre las 23
			    			// y las 00 hs, para así completar la búsqueda de las 4 hs anteriores
			    			
			    			else{
			    				
			    				log.debug( "ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. currentTime-iobLastTime>14405. ELSE");
			    				
			    				indices1 = subject_data.subjectBasal.find(">", -1, "<=", timeNowSecs/60);
			    				indices2 = subject_data.subjectBasal.find(">", 1440+timeNowSecs/60-240, "<=", 1440); 
			    				t0 = 1440+timeNowSecs/60-240;
			    				
			    			}
			    			
			    			// Si la diferencia temporal es mayor a 8 hs, entonces tomo los estados iniciales iguales a 0
			    			
			    			if(timeDiff>2*14400+5){
			    				
			    				log.debug( "ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. timeDiff>2*14400+5. IF");
			    				
		        				double[][] xTemp1 = {{0.0},{0.0},{0.0}};
				    			iobState = new Matrix(xTemp1);
	    			    		gController.getSafe().getIob().setX(iobState);
	    			    		
			    			}
			    			
			    			// Sino, ocurre que la diferencia temporal es mayor a 4 hs y menor a 8 hs. Por lo tanto, tomo
			    			// como estados iniciales los últimos informados y los dejo evolucionar a entrada 0 hasta que 
			    			// la diferencia temporal entre ellos y el tiempo actual sea de 4 hs.
			    			
			    			else{
			    				
			    				log.debug( "ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. timeDiff<2*14400+5. IF");
			    				
			    				double[][] uTemp = {{0.0}};
				    			Matrix u = new Matrix(uTemp);
				    			
	    			    		for(int jj = 0; jj < (timeDiff-14400)/60.0/gController.getSafe().getTs(); ++jj){ 
	    			    			
	    			    			gController.getSafe().getIob().stateUpdate(u);
				    			
	    			    		}
			    			}
			    		}
			    		
			    		// Si la diferencia temporal es menor a 4 hs, busco los índices no 4 hs hacia atrás, sino desde la última
			    		// actualización del IOB en adelante. Por ejemplo, si la última actualización fue hace 3 hs, buscaré 3 hs
			    		// hacia atrás
			    		
			    		else{
			    			
			    			log.debug( "ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. currentTime-iobLastTime<=14405");
			    			
		    				t0 = timeIobSecs/60; // El tiempo inicial es el de la última actualización
		    				
		    				// Si el tiempo actual es mayor a las 04 hs (recordar que la diferencia temporal es menor a 4 hs), ó el tiempo de 
		    				// la última actualización del IOB es menor que el tiempo actual, busco desde t0 hasta el tiempo actual
		    				
			    			if(timeNowSecs/60>=240 || timeIobSecs/60<timeNowSecs/60){
			    				
			    				log.debug( "ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. currentTime-iobLastTime<=14405. IF");
			    				
			    				indices1 = subject_data.subjectBasal.find(">", timeIobSecs/60, "<=", timeNowSecs/60);
			    				
			    				log.debug( "ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. currentTime-iobLastTime<=14405. IF" + "indices1 null?: " + (indices1 == null));
			    				
			    			}
			    			
			    			// Sino, tengo que considerar nuevamente que 1440 min y 0 min es el mismo punto. Por eso, busco desde t0 a
			    			// 1440 min y desde 0 min hasta el tiempo actual
			    			
			    			else{
			    				
			    				log.debug( "ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. currentTime-iobLastTime<=14405. ELSE");
			    				
			    				indices1 = subject_data.subjectBasal.find(">", -1, "<=", timeNowSecs/60);
			    				indices2 = subject_data.subjectBasal.find(">", timeIobSecs/60, "<=", 1440);
			    				
			    			}	
			    		}
			    		
			    		// indices1 e indices2 pueden dar null o ser de tamaño 0 en caso que no se haya encontrado nada (ver método find de la
			    		// clase Tvector en el paquete Sysman
			    		
			    		// Si indices1 da null
			    		if(indices1 == null){
			    			
			    			log.debug( "ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. indices1 null");
			    			
			    			// Si además indices2 da null, entonces voy a buscar quedarme con el último índice hasta el tiempo actual
			    			
			    			if(indices2 == null){
			    				
			    				log.debug( "ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. indices1 null & indices2 null");
			    				
			    				// Para quedarme con el último índice hasta el tiempo actual, primero capturo todos los posibles índices anteriores
			    				
			    				indices1 = subject_data.subjectBasal.find(">", -1, "<=", timeNowSecs/60);		// Find the list of indices <= time in minutes since today at 00:00
			    				
			    				// Si indices1 resulta null amplio la búsqueda a todos los índices
			    				
			    				if (indices1 == null) {
			    					
			    					indices1 = subject_data.subjectBasal.find(">", -1, "<", -1);				// Use final value from the previous day's profile
			    					
			    				}
			    				
			    				// Si indices1 resulta de tamaño 0 amplio la búsqueda a todos los índices
			    				
			    				else if (indices1.size() == 0) {
			    					
			    					indices1 = subject_data.subjectBasal.find(">", -1, "<", -1);				// Use final value from the previous day's profile
			    					
			    				}
			    				
			    				// En cualquier caso (al menos 1 resultado tiene que haber, ya que para funcionar el DiAs tiene que tener
			    				// cargada alguna insulina basal), me quedo con el último elemento de esta lista de índices
			    				
			    				indices.add(indices1.get(indices1.size()-1));
			    				
			    				// indices sólo tendrá un elemento que resulta ser forzadamente el último cambio en la velocidad de infusión.
			    				// Por ese motivo, para armar el vector de insulina basal no voy a considerar la posible velocidad anterior,
			    				// sino que se considera que por el tiempo establecido la infusión basal es únicamente la que se detectó.
			    				// Esto se indica poniendo en false flagAdd.
			    				
			    				flagAdd = false;
			    			}
			    			
			    			// Si indices2 no es null
			    			
			    			else{
			    				
			    				// Si es de tamaño 0
			    				
			    				if(indices2.size()==0){
			    					
			    					log.debug( "ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. indices1 null & indices2.size 0");
			    					
			    					// Aplico el mismo procedimiento que cuando indices2 era null
			    					
			    					indices1 = subject_data.subjectBasal.find(">", -1, "<=", timeNowSecs/60);		// Find the list of indices <= time in minutes since today at 00:00
			    					
				    				if (indices1 == null) {
				    					
				    					indices1 = subject_data.subjectBasal.find(">", -1, "<", -1);				// Use final value from the previous day's profile
				    					
				    				}
				    				
				    				else if (indices1.size() == 0) {
				    					
				    					indices1 = subject_data.subjectBasal.find(">", -1, "<", -1);				// Use final value from the previous day's profile
				    					
				    				}
				    				
				    				indices.add(indices1.get(indices1.size()-1));
				    				
				    				flagAdd = false;
				    				
			    				}
			    				
			    				// Si indices2 no es de tamaño 0, entonces indices es indices2
			    				
			    				else{
			    					
			    					log.debug( "ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. indices1 null & indices2 something");
			    					
			    					indices = indices2;
			    					
			    				}
			    				
			    			}
			    			
			    		}
			    		
			    		// Aplico el mismo prodecidimiento que antes si indices1 es de tamaño 0
			    		
			    		else if (indices1.size() == 0) {
			    			
			    			log.debug( "ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. indices1.size 0");
			    			
			    			if(indices2 == null){
			    				
			    				log.debug( "ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. indices1.size 0 & indices2 null");
			    				
			    				indices1 = subject_data.subjectBasal.find(">", -1, "<=", timeNowSecs/60);		// Find the list of indices <= time in minutes since today at 00:00
			    				
			    				if (indices1 == null) {
			    					
			    					indices1 = subject_data.subjectBasal.find(">", -1, "<", -1);				// Use final value from the previous day's profile
			    					
			    				}
			    				
			    				else if (indices1.size() == 0) {
			    					
			    					indices1 = subject_data.subjectBasal.find(">", -1, "<", -1);				// Use final value from the previous day's profile
			    					
			    				}
			    				
			    				indices.add(indices1.get(indices1.size()-1));
			    				
			    				flagAdd = false;
			    				
			    			}
			    			
			    			else{
			    				
			    				if(indices2.size()==0){
			    					
			    					log.debug( "ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. indices1.size & indices2.size 0");
			    					
			    					indices1 = subject_data.subjectBasal.find(">", -1, "<=", timeNowSecs/60);		// Find the list of indices <= time in minutes since today at 00:00
			    					
				    				if (indices1 == null) {
				    					
				    					indices1 = subject_data.subjectBasal.find(">", -1, "<", -1);				// Use final value from the previous day's profile
				    					
				    				}
				    				
				    				else if (indices1.size() == 0) {
				    					
				    					indices1 = subject_data.subjectBasal.find(">", -1, "<", -1);				// Use final value from the previous day's profile
				    					
				    				}
				    				
				    				indices.add(indices1.get(indices1.size()-1));
				    				
				    				flagAdd = false;
				    				
			    				}
			    				
			    				else{
			    					
			    					log.debug( "ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. indices1.size 0 & indices2 something");
			    					
			    					indices = indices2;
			    					
			    				}
			    				
			    			}
			    			
			    		}
			    		
			    		// Si indices1 no es null, ni de tamaño 0
			    		
			    		else{
			    			
			    			log.debug( "ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. indices1.size != 0");
			    			
			    			// Si indices2 es null, defino indices como indices1
			    			
			    			if(indices2 == null){
			    				
			    				log.debug( "ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. indices1.size != 0 & indices2 null");
			    				
			    				indices = indices1;
			    				
			    			}
			    			
			    			// Ídem para si indices2 es de tamaño 0
			    			
			    			else if(indices2.size() == 0){
			    				
			    				log.debug( "ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. indices1.size != 0 & indices2.size 0");
			    				
			    				indices = indices1;
			    				
			    			}
			    			
			    			// Si indices2 no es ni nulo, ni de tamaño 0, entonces indices es la combinación de indices1 y 2.
			    			// Por cómo están definidos, primero va indices2 y luego indices1
			    			
			    			else{
			    				
			    				log.debug( "ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. indices1.size != 0 & indices2 something");
			    				
			    				indices.addAll(indices2);
			    				indices.addAll(indices1);
			    				
			    			}
			    			
			    		}
			    		
			    		// Como lo que se detectan son cambios, el primer elemento de indices será el primer cambio, es decir, la infusión 
			    		// que se aplicará a partir de ese instante. Para el tiempo anterior, la velocidad de infusión es la asociada al índice 
			    		// anterior. Por eso, es que a menos que haya sido indicado lo contrario (forzando flagAdd = false), se debe agregar el índice
			    		// anterior como primer elemento
			    		
			    		if(flagAdd){
			    			
			    			// Si el primer elemento no es el 0, entonces significa que el anterior es -1 el primero
			    			
				    		if(indices.get(0)!=0){
				    			
				    			indices.add(0, indicesAux.get(indices.get(0).intValue()-1));
				    			
				    		}
				    		
				    		// Sino, el último de índicesAux (todos los índices) es el anterior al primero
				    		
				    		else{
				    			
				    			indices.add(0, indicesAux.size()-1);
				    			
				    		}
				    		
			    		}
			    		
			    		// Acá armo la lista de pares con velocidad de infusión y tiempo de inicio de la infusión para
			    		// luego aplicarle al modelo de IOB
			    		
			    		Iterator<Integer> it = indices.iterator(); // Armo un iterador con el vector de índices
			    		int ii     = 0;
			    		Pair p     = new Pair();
			    		long time  = 0;
			    		long timef = 0;
			    		double value;
			    		
			    		// Defino el primer par, que estará dado por la insulina basal del primer elemento de indices, con tiempo
			    		// inicial 0
			    		
			    		value = subject_data.subjectBasal.get_value(indices.get(0));
		    			p.put(time, value);
		    			iobInput.add(ii, p);
		    			
		    			// Debug
		    			
		    			log.debug("ARG /////// DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. iobInput[0]: " + iobInput.get(0).value() + ". indices.get(0): " + indices.get(0));
		    			
		    			//
		    			
		    			// Esta es una manera elegante de recorrer el vector
		    			
			    		while(it.hasNext())
			    		{
			    			Integer obj = it.next();
			    			
			    			log.debug("ARG /////// DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. Indices element: " + obj.intValue());
			    			
			    			if(ii!=0){
			    				
			    				// Capturo el tiempo del segundo índice
			    				
			    				time = subject_data.subjectBasal.get_time(obj.intValue());
			    				
			    				// Si el tiempo que capturé es menor que el inicial, entonces hago el corrimiento de 1440 min
			    				
			    				if(time-t0<0){
			    					
			    					time = time+1440;
			    					
			    				}
			    				
			    				// Calculo el tiempo en que se aplicará esa infusión basal
			    				
			    				timef = time-t0;
				    			value = subject_data.subjectBasal.get_value(obj.intValue());
				    			iobInput.add(ii, new Pair()); // Tengo que agregar un nuevo par, no puedo redefinir p
				    			iobInput.get(ii).put(timef, value);
				    			
				    			log.debug("ARG /////// DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. iobInput[ii].value: " + iobInput.get(ii).value() + ". iobInput[ii].time: " + iobInput.get(ii).time());
				    			
			    			}	    			    			

			    			++ii;
			    			
			    		}
			    		
			    		log.debug("ARG /////// DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. ii: " + ii);
			    		
			    		// Aplico el posible corrimiento de 1440 min al tiempo final
			    		
			    		if(tf-t0<0){
			    			
	    					tf = tf+1440;
	    					
	    				}
			    		
			    		timef = tf-t0;
			    		iobInput.add(ii, new Pair());
		    			iobInput.get(ii).put(timef, value); // El último elemento es el tiempo final y el valor de infusión basal del último índice (se mantiene ya que no había otro cambio)
		    			
		    			log.debug("ARG /////// DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. iobInput[end].value: " + iobInput.get(ii).value() + ". iobInput[end].time: " + iobInput.get(ii).time());	    			    			
		    			
		    			double uDouble = 0.0;
			    		int kk = 0;
			    		
			    		double totalBasal = 0.0; // Acá acumulo toda la cantidad en U de insulina basal que se aplicará
			    		
			    		for(int pp = 0; pp <= iobInput.size()-2; ++pp){
			    			
			    			totalBasal += (iobInput.get(pp).value()/60.0)*(iobInput.get(pp+1).time()-iobInput.get(pp).time()); // U/min x min
			    			
			    			log.debug("ARG /////// DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. iobInput.get(pp).value(): " + iobInput.get(pp).value()+ ". iobInput.get(pp).value()/60.0: " + iobInput.get(pp).value()/60.0 + ". time1: " + iobInput.get(pp+1).time() + ". time2: " + iobInput.get(pp).time() + ". totalBasalSum: " + (iobInput.get(pp).value()/60.0)*(iobInput.get(pp+1).time()-iobInput.get(pp).time()));
			    			
			    		}
			    		
			    		// Si la cantidad de insulina informada por el paciente es mayor o igual al 90% de la cantidad de insulina basal, entonces
			    		// se aplica el vector de insulina basal previamente obtenido, y en el tiempo medio un bolo de insulina que representa el 
			    		// exceso o defecto informado 
			    		
			    		if(iobInitBolus>=0.9*totalBasal){
			    			
			    			iobInitBolus -= totalBasal; // Defino el bolo (+/-) a aplicar a la mitad del intervalo
			    			
			    		}
			    		
			    		// Si lo anterior no se cumple, entonces no se infunde la insulina basal, y se aplica únicamente la insulina informada por el
			    		// paciente a la mitad del intervalo
			    		
			    		else{
			    			
			    			long timeAux = 0;
			    			
			    			for(int pp=0;pp <= iobInput.size()-1; ++pp){
			    				timeAux = iobInput.get(pp).time();
			    				iobInput.get(pp).put(timeAux, 0.0); // Defino el vector de infusión basal con 0's
			    			}
			    			
			    		}
			    		
			    		log.debug("ARG /////// DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. totalBasal: " + totalBasal + ". iobInitBolus: " + iobInitBolus + ". iobInput size: " + iobInput.size() + ". timef: " + timef);
			    		
			    		// ************************************************************************************************************ //
			    		// Actualizo los estados con la entrada definida anteriormente
			    				    			    		
			    		for(int jj = 0; jj < timef/gController.getSafe().getTs(); ++jj){
			    			
			    			// Comparo el tiempo siguiente con el almacenado en el vector de entrada. Si supero el de una
			    			// posición paso a la siguiente
			    			// El último elemento temporal de iobInput es timef, por ende nunca habrá desborde
			    			
			    			if((jj+1)*gController.getSafe().getTs()>=iobInput.get(kk+1).time()){
			    				++kk;
			    			}
			    			
			    			//if((jj+1)*gController.getSafe().getTs()==timef/2.0){
			    			
			    			// Cuando llego a la mitad del intervalo, aplico el bolo
			    			
			    			if(jj*gController.getSafe().getTs()==timef/2.0){
				    			uDouble = iobInput.get(kk).value()*100.0+100.0*iobInitBolus*60.0/gController.getSafe().getTs(); // U/h 2 pmol/min
			    			}
			    			
			    			else{
			    				uDouble = iobInput.get(kk).value()*100.0; // U/h 2 pmol/min
			    			}
			    			
			    			//iobEst = gController.getSafe().getIobEst(gController.getPatient().getWeight());
			    			//log.debug("ARG /////// DIAS_STATE_CLOSED_LOOP. uDouble: " + uDouble + ". iobEst: " + iobEst);
			    			
			    			double[][] uTemp = {{uDouble/gController.getPatient().getWeight()}}; // La entrada al modelo es en pmol/min/kg
			    			Matrix u = new Matrix(uTemp);
			    			gController.getSafe().getIob().stateUpdate(u);
			    			
			    		}
			    		// ************************************************************************************************************ //
			    		
			    		// Debug
			    		
			    		log.debug("ARG /////// DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. Total n iterations: " + timef/gController.getSafe().getTs() + ". kk: " + kk + ". uDouble: " + uDouble);
			    		
			    		//
			    		
			    		
			    		// ************************************************************************************************************ //
			    		// Guardo el IOB actualizado
			    				    			    		
			    		double iobEst   = gController.getSafe().getIobEst(gController.getPatient().getWeight());
			    		double iobBasal = gController.getSafe().getIobBasal(gController.getPatient().getBasalU(),gController.getPatient().getWeight());
			    		
			    		ContentValues statesTableIOB = new ContentValues();
			    		TableShortCut scTableIOB = new TableShortCut(); 
			    		double[][] iobStates = gController.getSafe().getIob().getX().getData();
			    		statesTableIOB = scTableIOB.insertValue(statesTableIOB, iobStates[0][0], iobStates[1][0], iobStates[2][0], iobEst, 
			    				iobBasal, 0.0, 0.0, 0.0, 0.0, 
			    				0.0, 0.0, 0.0, 0.0);
						getContentResolver().insert(Biometrics.USER_TABLE_1_URI, statesTableIOB);
						
						// Debug
			    		
			    		log.debug("ARG /////// DIAS_STATE_CL&OP&ST&SS. Inicialización IOB (Bolo de inicialización). timeDiff: " + timeDiff + ". IobState1: " + iobStates[0][0] + ". IobState2: " + iobStates[1][0] + ". IobState3: " + iobStates[2][0] +". Final IOB: " + iobEst + ". Basal IOB: "+iobBasal+ ". indicesAux Size: " + indicesAux.size() + ". indices Size: " + indices.size());
			    		
			    		//
			    		
			    		// ************************************************************************************************************ //
						
		        	}
		        	
		        	// Si no se detecta bolo de inicialización
		        	
		        	else{
		        		
		        		log.debug("ARG /////// DIAS_STATE_CL&OP&ST&SS. Inicialización IOB (Bolo de inicialización). IobState1: " + iobState1 + ". IobState2: " + iobState2 + ". IobState3: " + iobState3);
		        		
		        	}
		        	
		        	// ************************************************************************************************************ //
		    		// Manejo bolo asincrónico de corrección durante los últimos 5 min
		        	
		        	if(extraBolus!=0){
			        	
		        		// I consider that a bolus was applied at the end of the 5-min time interval
		        		
		        		double uDouble = 100.0*extraBolus*60.0/gController.getSafe().getTs();
		        		double[][] uTemp = {{uDouble/gController.getPatient().getWeight()}};
		    			Matrix u = new Matrix(uTemp);
		    			gController.getSafe().getIob().stateUpdate(u);
		    			
		    			// ************************************************************************************************************ //
			    		// Guardo el IOB actualizado
			        	
		    			double iobEst   = gController.getSafe().getIobEst(gController.getPatient().getWeight());
		    			double iobBasal = gController.getSafe().getIobBasal(gController.getPatient().getBasalU(),gController.getPatient().getWeight());
		    			
			    		ContentValues statesTableIOB = new ContentValues();
			    		TableShortCut scTableIOB     = new TableShortCut(); 
			    		double[][] iobStates = gController.getSafe().getIob().getX().getData();
			    		statesTableIOB = scTableIOB.insertValue(statesTableIOB, iobStates[0][0], iobStates[1][0], iobStates[2][0], iobEst, 
			    				iobBasal, 0.0, 0.0, 0.0, 0.0, 
			    				0.0, 0.0, 0.0, 0.0);
						getContentResolver().insert(Biometrics.USER_TABLE_1_URI, statesTableIOB);
						
						// Debug
			    		
			    		log.debug("ARG /////// DIAS_STATE_CL&OP&ST&SS. Inicialización IOB (Bolo de corrección). timeDiff: " + timeDiff + ". IobState1: " + iobStates[0][0] + ". IobState2: " + iobStates[1][0] + ". IobState3: " + iobStates[2][0] +". Final IOB: " + iobEst + ". Basal IOB: "+iobBasal);
			    		
			    		//
			    		
		        	}
		        	
		    		double iobEst   = gController.getSafe().getIobEst(gController.getPatient().getWeight());
		    		double iobBasal = gController.getSafe().getIobBasal(gController.getPatient().getBasalU(),gController.getPatient().getWeight());
		    		double[][] iobStates = gController.getSafe().getIob().getX().getData();
		    		
		    		// Debug
		    		
		    		log.debug("ARG /////// DIAS_STATE_CL&OP&ST&SS. Inicialización IOB (Final). IobState1: " + iobStates[0][0] + ". IobState2: " + iobStates[1][0] + ". IobState3: " + iobStates[2][0] +". Final IOB: " + iobEst + ". Basal IOB: "+iobBasal);
					
		    		//
		    		
		    		// ************************************************************************************************************ //
		    		// ************************************************************************************************************ //
		    		
		    		// Rutina para actualizar el vector de muestras del CGM
		    		
		    		// ************************************************************************************************************ //
		    		
		    		// El flag basalCase se utiliza para a través de su activación indicar la falta de información para computar
		    		// la acción de control, y por ende, el paso a la infusión a lazo abierto si el sistema está en Closed-Loop Mode
		    		
		    		boolean basalCase   = false; 
		    		int     rCFBolusIni = 0; // Variable para retardar el posible BAC en la inicialización
		    		
		    		// Este if es redundante, pero lo dejo para remarcar que la actualización del vector de muestras del CGM se
		    		// realiza en cualquier modo dado con el sensor conectado recibiendo información
		    		
		    		if (DIAS_STATE == State.DIAS_STATE_CLOSED_LOOP || DIAS_STATE == State.DIAS_STATE_SENSOR_ONLY || DIAS_STATE == State.DIAS_STATE_OPEN_LOOP || DIAS_STATE == State.DIAS_STATE_STOPPED){
			    		
		    			// ************************************************************************************************************ //
		        		// Capturo el último valor medido por el CGM y verifico que existan registros en la tabla de CGM
						
			    		double  yCGM        = 0.0; 			 // Variable con la última medición del CGM
			    		double  cgmF        = 0.0; 			 // Medición del CGM filtrada por el NSF. Como el NSF no se utiliza, es igual a la medición
			    		long    timeCGM     = 0;   			 // Tiempo de la última medición de CGM
			    		long    diffCGMTime = currentTime;   // Diferencia entre el tiempo actual y el último registro de la tabla de CGM
			    		boolean cgmOK       = true;			 // Flag para indicar que la señal de CGM es apta
			    		int     state       = 0;   			 // Variable con el estado del CGM
			    		
			    		// Puntero a la tabla de CGM
			    		
			    		Cursor cCGM = getContentResolver().query(Biometrics.CGM_URI, null, null, null, null);
			    		
			    		long offsetR = 300; // Offset para considerar la diferencia temporal en segundos entre que el receptor
			    						    // envía una nueva muestra y el APC se ejecuta. Como máximo, dado que ambas cosas ocurren
			    							// cada 5 min, la diferencia será de 5 min
			    		
			        	if (cCGM != null) {
			        		if (cCGM.moveToLast()) {
			        			
			        			yCGM        = cCGM.getDouble(cCGM.getColumnIndex("cgm"));
			        			timeCGM     = cCGM.getLong(cCGM.getColumnIndex("time"));
			        			cgmF        = yCGM;
		        				state       = cCGM.getInt(cCGM.getColumnIndex("state"));
		        				
		        				if(Objects.equals(timeCGM, null)){
		        				
		        					// Debug
		    			    		
		    			    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. timeCGM = null"
		    			    				+ "--> diffCGMTime = currentTime");
		    			    		
		    			    		//
		    			    		
		        					diffCGMTime = currentTime;
		        					
		        				}
		        				
		        				else{
		        					
		        					diffCGMTime = currentTime - timeCGM;
		        					
		        				}
		        				
		        				// Imprimo en pantalla errores del sensor
		        				// 1: data error
		        				// 10: sensor failed
		        				
		        				if(state==1 || state ==10){
		        					
		        					// Debug
		    			    		
		    			    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. Error. " + 
		    			    		"diffCGMTime: "+diffCGMTime+". state:" +state);
		    			    		
		    			    		//
		    			    		
		        					Toast.makeText(IOMain.this, "Data error or sensor failed. Check CGM sensor!" , Toast.LENGTH_SHORT).show();
		        					
		        					cgmOK = false;
		        					
		        				}
		        				
		        				// Si el estado del CGM es 5: warmup, entonces el sensor estará durante 2 hs
		        				// en el proceso de warm-up y enviará 0s. En ese caso, no se procede con la
		        				// actualización del vector de CGM
		        				
		        				if(state==5){
		        					
		        					// Debug
		    			    		
		    			    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. Warm-up period. " + "diffCGMTime: "+diffCGMTime+". state:" +state);
		    			    		
		    			    		//
		    			    		
		        					cgmOK = false;
		        					
		        				}
		        				
		        				else{
		        				
			        				// Si la diferencia entre el tiempo actual y el último de la tabla de CGM es mayor
			        				// o igual a offsetR,
			        				// entonces detecto pérdida de conexión.
			        				
			        				// La máxima diferencia tolerable es de 300 s. Si el sincronismo es perfecto, es decir
			        				// cada iteración diffCGMTime = 0, entonces una pérdida de sincronismo es de 301 s. 
			        				// Si contemplo tiempo mayores puedo perder la detección de la pérdida de sincronismo
			        				// en esos casos.
			        				
			        				if(diffCGMTime > offsetR){
			        					
			        					// Acá no seteo el flag cgmOK a false, porque en el caso en que exista una desconexión, el controlador
			        					// puede seguir funcionando a través de estimaciones salvo que la diferencia temporal sea mayor o igual a 20 min
			        					
			        					// Debug
			    			    		
			    			    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. CGM connection failed. " +
			    			    		"diffCGMTime: "+diffCGMTime);
			    			    		
			    			    		//
			    			    		
			    			    		// Las estimaciones no pueden seguir si la diferencia temporal es mayor o igual a 20 min. 
			    			    		// El DiAs pasará a Stop Mode
			    			    		// y el vector de CGM no se actualizará hasta que no se reestablezca la conexión
			    			    		
			    			    		if(diffCGMTime > 1200){ // 1200 seg define el mínimo. Es decir, si están perfectamente sincronizados
			    			    			// DiAs con CGM en 20 min de desconexión será 1200. Si están desfazados será mayor.
			    			    			
			    			    			cgmOK = false;
			    			    			
			    			    		}
			    			    		
			        				}
			        				
			        				else{
			        					
			        					// Debug
			    			    		
			    			    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. CGM connection stablished. " +
			    			    		"diffCGMTime: "+diffCGMTime);
			    			    		
			    			    		//
			    			    		
			        				}

				        		}
			        		}
			        		
			        		else{
			        			
			        			cgmOK = false;
			        			
			        			// Debug
			        			
	    			    		log.error("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. CGM table empty!");
	    			    		
	    			    		//
	    			    		
			        		}
			        		
			        	}
			        	
			        	else{
				    		
			        		cgmOK = false;
			        		
			        		// Debug
			        		
				    		log.error("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. Error loading CGM table!");
				    		
				    		//
				    		
				    		Toast.makeText(IOMain.this, "Error loading CGM table!" , Toast.LENGTH_SHORT).show();
				    		
			        	}  
			        	
				        // Tanto cuando la tabla de CGM está posiblemente vacía o cuando existe un error al cargarla,
			        	// el flag cgmOK se setea a false, ya que en esas situaciones no se puede armar el vector
			        	// CGM
			        	
			        	cCGM.close();
			        	
			        	// ************************************************************************************************************ //
			        	// Acá se actualiza el vector de muestras del CGM si es posible (depende del cgmOK)
		        		
		        		long   timeCGMV      = 0;           // Tiempo del último registro de la tabla del vector de CGM
		        		long   diffCGMVTime  = 0;			// Diferencia entre el tiempo actual y el último de la tabla del vector de CGM
		        		double flag2c2       = 0.0;         // Flag para indicar el contemplar el caso 2c2 indicado en el manual
		        											// Lo defino como double porque se guarda en un columna double de la 
		        											// tabla de usuario 4
		        		long   timeStampCgmV = currentTime; // Time-stamp que para el caso 2c2
		        		
		        		// Puntero a la tabla del vector de CGM (tabla de usuario 4)
		        		Cursor cCGMV = getContentResolver().query(Biometrics.USER_TABLE_4_URI, null, null, null, null);
		        		
		        		// Si existe la tabla CGM está OK se procede con la actualización
		        		if (cgmOK){
				        	if (cCGMV != null) {
				        		if (cCGMV.moveToLast()) {
				        			
				        			timeCGMV = cCGMV.getLong(cCGMV.getColumnIndex("time"));
				        			flag2c2  = cCGMV.getDouble(cCGMV.getColumnIndex("d15"));
				        			
				        			// Cargo todos las mediciones almacenadas en la tabla
				        			// Desde "d0": muestra más antigua, hasta "d5": muestra más reciente
				        			
				        			double cgmAux = 0.0;
				        			
				        			for(int ii = 0; ii < gController.getEstimator().getCgmVector().getM(); ++ii){
				        				cgmAux = cCGMV.getDouble(cCGMV.getColumnIndex("d"+ii));
				        				gController.getEstimator().insert(cgmAux);
				        			}
				        			
				        			diffCGMVTime = currentTime - timeCGMV;
				        			
				        			double[][] cgmVector = gController.getEstimator().getCgmVector().getData();
				        			
	    			        		// Debug
	    			        		
	    			        		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. diffCGMVTime: "+diffCGMVTime+
	    			        				". CGM Vector: " +" [0]: " + cgmVector[0][0] + ". [1]: " + cgmVector[1][0] + ". [2]: " + cgmVector[2][0] + 
	    			        				". [3]: " + cgmVector[3][0] + ". [4]: " + cgmVector[4][0] + ". [5]: " + cgmVector[5][0]);
		    			    		
		    			    		//
	    			        		
				        			// diffCGMVTime indicará la existencia o no de sincronismo en el vector de CGM
				        			
				        			// diffCGMVTime = 300 s indica que en la iteración anterior se actualizó el vector
				        			// de CGM
				        			// diffCGMVTime = 600 s indica que hay un delay de 5 min
				        			// diffCGMVTime = 900 s indica que hay un delay de 10 min
				        			// diffCGMVTime = 1200 s indica que hay un delay de 15 min
				        			
				        			// Vuelvo a chequear que exista conexión con el sensor, para establecer
				        			// si la muestra medida es actual (<= 300 s)
				        			
			        				if(diffCGMTime <= offsetR){
			        					
			        					// Si hay conexión, detecto si hay sincronismo en el vector de muestras de CGM
			        					
			        					// Si diffCGMVTime < diffCGMTime significa que ya usé esa muestra CGM para la
			        					// actualización del vector de CGM, por eso en ese caso, no debería agregar
			        					// nuevamente la misma muestra
			        					
			        					if(diffCGMVTime<diffCGMTime){
			        					
			        						timeStampCgmV = timeCGMV; // No actualizo el vector de tiempo
			        						
			        						// Debug
			        						
			        						log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. "
			        								+ "No corresponde, muestra ya considerada");
			        						
			        						//
			        						
			        					}	
			        					
			        					// Para eso verifico que la última actualización sea a lo sumo de 5 min (305 s, 5 s de margen)
			        					// Otra alternativa es que haya conexión, pero no sincronismo y que haya caído 
			        					// en la iteración anterior en el caso 2c2 (flag2c2 activado)
			        					
			        					else if(diffCGMVTime < 305 || (flag2c2==1 && diffCGMVTime<1505)){
			        						
			        						// Case 2)a)
			        						
			        						gController.getEstimator().insert(cgmF); // Inserto la nueva muestra
			        						flag2c2 = 0; // Desactivo el flag2c2
			        						
			        						// Debug
				    			    		
				    			    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. Case 2.a");
				    			    		
				    			    		//
			        						
			        					}
			        					
			        					// Si hay conexión pero hay pérdida de sincronismo
			        					
			        					// Si la pérdida de sincronismo es de a lo sumo 15 min
			        					
			        					// Esto puede deberse a pérdida de conexión con el CGM (se detecta con diffCGMTime)
			        					// o a que se inicia el sistema luego de mucho tiempo
			        					
			        					else if(diffCGMVTime < 1205){
			        						
			        						// Hay conexión, pero hay pérdida de sincronismo (remediable por extrapolación)
			        						
			        						// Case 2)b1)
			        						
			        						// Calculo el número de tiempos de muestreo de diferencia
			        						// Por ejemplo, si la diferencia temporal es de 600 s, nIter será 2
			        						// Puede ocurrir que por apagar y prender el celular diffCGMVTime no sea múltiplo entero de 300
			        						// En ese caso, con esta definición el máximo error es el de un corrimiento de 2.5 min
			        						// Por ejemplo, si diffCGMVTime es menor a 450 s, nIter será 1, si es mayor a 450 y menor a 600 s,
			        						// nIter será 2
			        						
			        						int nIter = (int)Math.round(diffCGMVTime/300.0);
			        						
			        						double yCGMP = 0.0;
			        						double cgmPF = 0.0;
			        						
			        						// El número de muestras a agregar por extrapolación es nIter-1
			        						
			        						for(int ii = 0; ii < nIter-1; ++ii){ 
			        							gController.getEstimator().updatePred(5); // Extrapolo a 5 min
			        							yCGMP = gController.getEstimator().getPred(); // Capturo la predición
			        							cgmPF = Math.round(yCGMP); // Redondeo la muestra que almaceno al número entero más próximo
			        							 						   // No tiene sentido guardar una muestra con decimales
			        							
			        							// Saturo la predicción de acuerdo a los límites del sensor
			        							
			        							if (cgmPF>400){
			        								cgmPF = 400;
			        							}
			        							else if (cgmPF<40){
			        								cgmPF = 40;
			        							}
			        							
			        							gController.getEstimator().insert(cgmPF); // Inserto la muestra
			        						}
			        						
			        						// Inserto finalmente la muestra recientemente capturada por el sensor
			        						
			        						gController.getEstimator().insert(cgmF); 
			        												        						
			        						// Debug
				    			    		
				    			    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. Case 2.b1");
				    			    		
				    			    		//
			        						
			        					}
			        					
			        					// Si la pérdida de sincronismo es mayor a 15 min y hay conexión, tengo que reinicializar
			        					// el vector de muestras
			        					
			        					else{
			        						
			        						// Hay conexión, pero hay pérdida de sincronismo (no remediable por extrapolación)
			        						
			        						// Case 2)b2) = Case 1)a)
			        						
			        						log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. "
					        						+ "Case 2.b2. ARRANCO.");
					        				
					        				// Reviso si existen registros previos del CGM 30 min atrás
								        	
								        	double cgmAux1   = 0;      
								        	double cgmAux2   = cgmF;    
								        	long timeCGMAux1 = timeCGM;
								        	long timeCGMAux2 = 0;
								        	int jj           = gController.getEstimator().getCgmVector().getM();
								        	
								        	// Vector para capturar las muestras del CGM
								        	
								        	double[][] cgmVectorAux = new double[gController.getEstimator().getCgmVector().getM()][1];
								        	
								        	// Puntero con los 25 min anteriores al último registro actual en forma descendiente
								        	
								        	Cursor cCGMAux = getContentResolver().query(Biometrics.CGM_URI,null,
								    		         "time>?" + " AND " + "time<?", new String[]{ Long.toString(timeCGM-1505) , Long.toString(timeCGM-1) }, "time DESC");
								        	
								        	// Flag para indicar el fin del bucle
								        	
								        	boolean flagEndIni = false;
								        	
								        	// Genero inicialmente el vector con todos los elementos iguales a la muestra reciente
								        	
								        	for(int ii = 0; ii < gController.getEstimator().getCgmVector().getM(); ++ii){
								        		cgmVectorAux[ii][0] = cgmF;
			    			        		}
								        	
								        	// Busco actualizar las muestras anteriores a la reciente si es que se puede
								        	
								        	if (cCGMAux != null) {
									    		while(cCGMAux.moveToNext() && !flagEndIni){
									    			
									    			timeCGMAux2 = cCGMAux.getLong(cCGMAux.getColumnIndex("time"));
									    			cgmAux1     = cCGMAux.getDouble(cCGMAux.getColumnIndex("cgm"));
									    			
									    			if(Objects.equals(timeCGMAux2, null)){
									    				
									    				flagEndIni = true;
									    				
									    				// Completo las muestras más antiguas iguales a 
									    				// la última muestra hallada
									    				
									    				for(int kk = 0; kk < jj-1; ++kk){ 
									    					cgmVectorAux[kk][0] = cgmAux2;
									    				}
									    				
									    			}
									    			
									    			else{
									    				
									    				// Sincronismo entre las muestras
									    				
										    			if(timeCGMAux1-timeCGMAux2<305){
										    				
										    				if(timeCGMAux1-timeCGMAux2>5){ // Evito muestras repetidas
										    					
											    				cgmVectorAux[jj-2][0] = cgmAux1;
											    				timeCGMAux1 = timeCGMAux2;
											    				cgmAux2 = cgmAux1;
											    				jj = jj-1;
											    				
											    				// Si por x motivo hay más de 5 muestras halladas en los
											    				// último 25 min, termino el while con lo que hallé
											    				
											    				if(jj-2<0){
											    					flagEndIni = true;
											    				}
											    				
										    				}
										    				
										    			}
										    			
										    			// Si la diferencia es mayor a 5 min completo las muestras 
										    			// más antiguas iguales a la última muestra hallada
										    			
										    			else{
										    				
										    				for(int kk = 0; kk < jj-1; ++kk){
										    					cgmVectorAux[kk][0] = cgmAux2;
										    				}
										    				
										    				flagEndIni = true;
										    				
										    			}
										    			
									    			}
									    			
									    			log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. Case 2.b2."
									    					+ ". cgm: "+cgmAux1);
									    			
									    		}
									    		
									    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. Prueba. "+
				    			        				". CGM Vector: " +" [0]: " + cgmVectorAux[0][0] + ". [1]: " + cgmVectorAux[1][0] + ". [2]: " + 
									    				cgmVectorAux[2][0] + ". [3]: " + cgmVectorAux[3][0] + ". [4]: " + cgmVectorAux[4][0] +
									    				". [5]: " + cgmVectorAux[5][0]+". jj: "+jj);
									    		
								        	}
								        	
								        	// Actualizo el vector de CGM con el vector generado
								        	
								        	for(int ii = 0; ii < gController.getEstimator().getCgmVector().getM(); ++ii){
					        					gController.getEstimator().insert(cgmVectorAux[ii][0]);
			    			        		}
								        	
								        	rCFBolusIni = jj-1; // Número de iteraciones de delay para el BAC
								        	
					        				// Debug
				    			    		
				    			    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. "
				    			    				+ "Case 2.b2. TERMINO.");
				    			    		
				    			    		//
				    			    		
				    			    		cCGMAux.close();
				    			    		
			        					}
			        					
			        				}
			        				
			        				// Si no hay conexión con el sensor CGM
			        				
			        				else{
			        					
			        					// Verifico que haya sincronismo en el vector de muestras CGM
			        					
			        					// El DiAs automáticamente pasados los 20 min sin señal de CGM pasa a Stop Mode
			        					// Como en Stop Mode se sigue actualizando el vector de CGM, se fuerza evitar caer indefinidamente
			        					// en 2c1 mediante el flag cgmOK, que es desactivado cuando la diferencia entre el tiempo
			        					// actual y el del último registro de la tabla de CGM es mayor a 20 min
			        					
			        					// Para tomar en cuenta, cuando el sensor está en warm-up envía 0 como medición. Es decir
			        					// sigue transmitiendo. Para evitar caer indefinidamente en 2.c1 en ese caso, se mira
			        					// la variable state de la tabla CGM_table que en ese caso vale 5 (en caso normal 0).
			        					
			        					if(diffCGMVTime < 305){
			        						
			        						// No hay conexión, pero hay sincronismo
			        						
			        						// Case 2)c1)
			        						
			        						double yCGMP = 0.0;
			        						double cgmPF = 0.0;
			        						
			        						gController.getEstimator().updatePred(5); // Extrapolo a 5 min
		        							yCGMP = gController.getEstimator().getPred(); // Capturo la predicción
		        							cgmPF = Math.round(yCGMP); // Redondeo la predicción
		        							
		        							// Saturo la predicción de acuerdo a los límites del sensor
		        							
		        							if (cgmPF>400){
		        								cgmPF = 400;
		        							}
		        							else if (cgmPF<40){
		        								cgmPF = 40;
		        							}
		        							
		        							gController.getEstimator().insert(cgmPF); // Inserto la muestra
		        												        							
			        						// Debug
				    			    		
				    			    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. Case 2.c1");
				    			    		
				    			    		//
				    			    		
			        					}
			        					
			        					// Si hay pérdida de sincronismo menor o igual a 15 min
			        					
			        					else if(diffCGMVTime < 1205){
			        						
			        						// Si no hay conexión y hay pérdida de sincronismo menor o igual a 15 min
			        						
			        						// Case 2)c2)
			        												        						
			        						// Como en el caso 2)b1), calculo el número de tiempos de muestreo de diferencia
			        						
			        						int nIter = (int)Math.round(diffCGMVTime/300.0);
			        						
			        						double yCGMP = 0.0;
			        						double cgmPF = 0.0;
			        						
			        						// El número de muestras a agregar por extrapolación es nIter
			        						
			        						for(int ii = 0; ii < nIter; ++ii){
			        							gController.getEstimator().updatePred(5); // Extrapolo a 5 min
			        							yCGMP = gController.getEstimator().getPred(); // Obtengo la predicción
			        							cgmPF = Math.round(yCGMP); // Redondeo la predicción
			        							
			        							// Saturo la predicción de acuerdo a los límites del sensor
			        							
			        							if (cgmPF>400){
			        								cgmPF = 400;
			        							}
			        							else if (cgmPF<40){
			        								cgmPF = 40;
			        							}
			        							
			        							gController.getEstimator().insert(cgmPF); // Inserto la muestra
			        						}
			        						
			        						// Si la pérdida de sincronismo fue de 15 min, no se guarda el time-stamp nuevo. 
			        						// De esta forma, se hace pesar que las muestras se generaron y no fueron realmente medidas. 
			        						// Aquí surge un problema. Si hubo una pérdida de sincronismo de 15 min y no había conexión,
			        						// en la siguiente iteración por más que haya conexión, la diferencia de sincronismo sería de 20 min, 
			        						// lo que haría caer en la situación 2b2. Para evitar eso, se activa el flag2c2. De esa forma,
			        						// si hay conexión y el flag está activo, no se toma en cuenta la falta de sincronismo y se 
			        						// agrega la nueva muestra con el nuevo time-stamp (ver caso 2)a)). 
			        						
			        						if(nIter==4)
			        						{
			        						
			        							flag2c2       = 1;
				        						timeStampCgmV = timeCGMV;
				        						
				        						// Debug
					    			    		
					    			    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. Case 2.c2. "
					    			    				+ "Flag2c2 activado");
					    			    		
					    			    		//
				        						
			        						}
			        						
			        						
			        						// Debug
				    			    		
				    			    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. Case 2.c2");
				    			    		
				    			    		//
				    			    		
			        					}
			        					
			        					// Pérdida de sincronismo por más de 15 min
			        					
			        					else{
			        						
			        						// No hay conexión y hay pérdida de sincronismo por más de 15 min
			        						
			        						// Case 2)c3) = Case 1)b)
			        						
			        						// No hay información para armar el vector CGM, por lo que se pasa
			        						// a la infusión a lazo abierto si el sistema está en Closed-Loop Mode
			        						
			        						basalCase = true; 
			        						
			        						// Debug
				    			    		
				    			    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. Case 2.c3");
				    			    		
				    			    		//
			        						
			        					}
			        					
			        				}
			        										        				
				        		} 
				        		
				        		// No se detectan registros (puede que haya pero al iniciar luego de mucho tiempo moveToLast() puede
				        		// devolver false)
				        		
				        		else{
				        			
				        			// Si hay conexión
				        			
				        			if(diffCGMTime<=offsetR){
				        				
				        				// No detecto registros previos del vector de CGM y hay conexión
				        				
				        				// Case 1)a)
				        				
		        						log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. "
				        						+ "Case 1.a. ARRANCO.");
				        				
				        				// Reviso si existen registros previos del CGM 30 min atrás
							        	
							        	double cgmAux1   = 0;      
							        	double cgmAux2   = cgmF;    
							        	long timeCGMAux1 = timeCGM;
							        	long timeCGMAux2 = 0;
							        	int jj           = gController.getEstimator().getCgmVector().getM();
							        	
							        	// Vector para capturar las muestras del CGM
							        	
							        	double[][] cgmVectorAux = new double[gController.getEstimator().getCgmVector().getM()][1];
							        	
							        	// Puntero con los 25 min anteriores al último registro actual en forma descendiente
							        	
							        	Cursor cCGMAux = getContentResolver().query(Biometrics.CGM_URI,null,
							    		         "time>?" + " AND " + "time<?", new String[]{ Long.toString(timeCGM-1505) , Long.toString(timeCGM-1) }, "time DESC");
							        	
							        	// Flag para indicar el fin del bucle
							        	
							        	boolean flagEndIni = false;
							        	
							        	// Genero inicialmente el vector con todos los elementos iguales a la muestra reciente
							        	
							        	for(int ii = 0; ii < gController.getEstimator().getCgmVector().getM(); ++ii){
							        		cgmVectorAux[ii][0] = cgmF;
		    			        		}
							        	
							        	// Busco actualizar las muestras anteriores a la reciente si es que se puede
							        	
							        	if (cCGMAux != null) {
								    		while(cCGMAux.moveToNext() && !flagEndIni){
								    			
								    			timeCGMAux2 = cCGMAux.getLong(cCGMAux.getColumnIndex("time"));
								    			cgmAux1     = cCGMAux.getDouble(cCGMAux.getColumnIndex("cgm"));
								    			
								    			if(Objects.equals(timeCGMAux2, null)){
								    				
								    				flagEndIni = true;
								    				
								    				// Completo las muestras más antiguas iguales a 
								    				// la última muestra hallada
								    				
								    				for(int kk = 0; kk < jj-1; ++kk){ 
								    					cgmVectorAux[kk][0] = cgmAux2;
								    				}
								    				
								    			}
								    			
								    			else{
								    				
								    				// Sincronismo entre las muestras
								    				
									    			if(timeCGMAux1-timeCGMAux2<305){
									    				
									    				if(timeCGMAux1-timeCGMAux2>5){ // Evito muestras repetidas
									    					
										    				cgmVectorAux[jj-2][0] = cgmAux1;
										    				timeCGMAux1 = timeCGMAux2;
										    				cgmAux2 = cgmAux1;
										    				jj = jj-1;
										    				
										    				// Si por x motivo hay más de 5 muestras halladas en los
										    				// último 25 min, termino el while con lo que hallé
										    				
										    				if(jj-2<0){
										    					flagEndIni = true;
										    				}
										    				
									    				}
									    				
									    			}
									    			
									    			// Si la diferencia es mayor a 5 min completo las muestras 
									    			// más antiguas iguales a la última muestra hallada
									    			
									    			else{
									    				
									    				for(int kk = 0; kk < jj-1; ++kk){
									    					cgmVectorAux[kk][0] = cgmAux2;
									    				}
									    				
									    				flagEndIni = true;
									    				
									    			}
									    			
								    			}
								    			
								    			log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. Case 1.a."
								    					+ ". cgm: "+cgmAux1);
								    			
								    		}
								    		
								    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. Prueba. "+
			    			        				". CGM Vector: " +" [0]: " + cgmVectorAux[0][0] + ". [1]: " + cgmVectorAux[1][0] + ". [2]: " + 
								    				cgmVectorAux[2][0] + ". [3]: " + cgmVectorAux[3][0] + ". [4]: " + cgmVectorAux[4][0] +
								    				". [5]: " + cgmVectorAux[5][0]+". jj: "+jj);
								    		
							        	}
							        	
							        	// Actualizo el vector de CGM con el vector generado
							        	
							        	for(int ii = 0; ii < gController.getEstimator().getCgmVector().getM(); ++ii){
				        					gController.getEstimator().insert(cgmVectorAux[ii][0]);
		    			        		}
							        	
							        	rCFBolusIni = jj-1; // Número de iteraciones de delay para el BAC
							        	
				        				// Debug
			    			    		
			    			    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. "
			    			    				+ "Case 1.a. TERMINO.");
			    			    		
			    			    		//
			    			    		
			    			    		cCGMAux.close();
			    			    		
				        			}
				        			
				        			// No hay conexión
				        			
				        			else{
				        				
				        				// No hay información para armar el vector CGM, por lo que se pasa
		        						// a la infusión a lazo abierto si el sistema está en Closed-Loop Mode
				        				
				        				basalCase = true;
				        				
				        				// Debug
			    			    		
			    			    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. Case 1.b");
			    			    		
			    			    		//
				        				
				        			}
		    			    		
				        		}
				        		
				        	}
				        	
				        	// No se detecta tabla del vector de CGM
				        	
				        	else{
				        		
				        		// Si hay conexión
				        		
				        		if(diffCGMTime<=offsetR){
			        				
					        		// No detecto registros previos del vector de CGM y hay conexión
			        				
			        				// Case 1)a)
			        				
			        				// Genero el vector de CGM con todos los elementos iguales a la muestra reciente
				        			
				        			for(int ii = 0; ii < gController.getEstimator().getCgmVector().getM(); ++ii){
			        					gController.getEstimator().insert(cgmF);
	    			        		}
			        										        				
			        				// Debug
		    			    		
		    			    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. Case 1.a. cCGMV == null");
		    			    		
		    			    		//
		    			    		
		    			    		Toast.makeText(IOMain.this, "Error loading User Table 4: CGM Vector" , Toast.LENGTH_SHORT).show();
		    			    		
			        			}
				        		
				        		// No hay conexión
				        		
			        			else{
			        				
			        				// No hay información para armar el vector CGM, por lo que se pasa
	        						// a la infusión a lazo abierto si el sistema está en Closed-Loop Mode
			        				
			        				basalCase = true;
			        				
			        				// Debug
		    			    		
		    			    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. Case 1.b");
		    			    		
		    			    		//
			        				
			        			}
				        		
				        	}
				    		
		        		}
		        		
		        		// Si la tabla CGM no está OK
		        		
		        		else{
		        			
		        			// No hay información para armar el vector CGM, por lo que se pasa
							// a la infusión a lazo abierto si el sistema está en Closed-Loop Mode
		        			
		        			basalCase = true;
		        			
		        			// Debug
				    		
				    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. Case 1.b");
				    		
				    		//

		        		}
		        		
		        		cCGMV.close();
		        		
		        		// ************************************************************************************************************ //

		        		// Rutina para guardar el vector de muestras de CGM
			        	
		        		
		        		// La activación del flag basalCase indica que no hay información suficiente del CGM 
		        		// para actualizar el vector de muestras del CGM
		        		
		        		// Si no se activó el flag basalCase
		        		
		        		if(!basalCase)
		        		{
		        			
		        			// ************************************************************************************************************ //
		        			// Guardo vector de muestras CGM
		        			// ************************************************************************************************************ //
		        			
		        			// Debug
				    		
				    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. Save CGM Vector");
				    		
				    		//
				    		
				    		ContentValues cgmVectorTable = new ContentValues();
				    		TableShortCut scTableCgm = new TableShortCut(); 
				    		double[][] cgmVector = gController.getEstimator().getCgmVector().getData();
				    		cgmVectorTable = scTableCgm.insertValue(cgmVectorTable, cgmVector[0][0], cgmVector[1][0], cgmVector[2][0], 
				    				cgmVector[3][0], cgmVector[4][0], cgmVector[5][0], 0.0, 0.0, 0.0, 
				    				0.0, 0.0, 0.0, 0.0);
				    		cgmVectorTable.put("time", timeStampCgmV);
				    		cgmVectorTable.put("d15", flag2c2);
				    		
				    		getContentResolver().insert(Biometrics.USER_TABLE_4_URI, cgmVectorTable);
				    		
				    		// Debug
				    		
				    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Actualización vector CGM. CGM Vector: " +
				    		" [0]: " + cgmVector[0][0] + ". [1]: " + cgmVector[1][0] + ". [2]: " + cgmVector[2][0] + ". [3]: " + cgmVector[3][0] + 
				    		". [4]: " + cgmVector[4][0] + ". [5]: " + cgmVector[5][0] + ". timeStampCgmV: " + timeStampCgmV + ". flag2c2: " + flag2c2);
				    		
				    		//
				    		
				    		if(rCFBolusIni!=0){
				    			
	    			    		// Puntero a la tabla del controlador
	    			    		
	    			    		Cursor cKStates = getContentResolver().query(Biometrics.HMS_STATE_ESTIMATE_URI, null, null, null, null);
	    			    		int rCFBolus = 0;
	    			    		
	    			    		if (cKStates != null) {
					        		
					        		if (cKStates.moveToLast()) {
					        									        				
					        			lastTime = cKStates.getLong(cKStates.getColumnIndex("time"));
					        			rCFBolus = (int)cKStates.getDouble(cKStates.getColumnIndex("correction_in_units"));
					        			
					        			// Debug
			    			    		
			    			    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Inicialización rCFBolus."
			    			    				+ " lastTime: "+lastTime+". rCFBolus: "+rCFBolus);
			    			    		
			    			    		//
			    			    		
					        			if (Objects.equals(lastTime, null)){
					        				
					        				lastTime = currentTime;
					        				
					        				// Debug
				    			    		
				    			    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Inicialización rCFBolus."
				    			    				+ " lastTime null --> currentTime");
				    			    		
				    			    		//
					        				
					        			}
					        			
					        		}
					        		
					        		else{
					        			
					        			lastTime = currentTime;
					        			
					        			// Debug
			    			    		
			    			    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Inicialización rCFBolus."
			    			    				+ " moveToLast() null --> currentTime");
			    			    		
			    			    		//
					        			
					        		}
					        		
					        		if(lastTime == currentTime){
							        	
		        			    		ContentValues statesTableK = new ContentValues();
		        			    		
		        			    		statesTableK.put("time", currentTime);
		        			    		statesTableK.put("correction_in_units", (double)rCFBolusIni);

		        						getContentResolver().insert(Biometrics.HMS_STATE_ESTIMATE_URI, statesTableK);
		        						
					        		}
					        		
					        		else{
					        			
					        			ContentValues statesTableK = new ContentValues();
		        			    		
					        			long diffT = currentTime-lastTime;
						        		
						        		int nIter = (int)Math.round(diffT/300.0);
						        		
						        		
						        		if (nIter-1>0){
						        			if(rCFBolus>=rCFBolusIni+nIter-1){
						        				statesTableK.put("correction_in_units", (double)(rCFBolus));
						        			}
						        			else{
						        				statesTableK.put("correction_in_units", (double)(rCFBolusIni+nIter-1));
						        			}
						        		}
						        		else{
						        			if(rCFBolus>=rCFBolusIni){
						        				statesTableK.put("correction_in_units", (double)rCFBolus);
						        			}
						        			else{
						        				statesTableK.put("correction_in_units", (double)(rCFBolusIni));
						        			}
						        			
						        		}
		        			    		

		        						getContentResolver().update(Biometrics.HMS_STATE_ESTIMATE_URI, statesTableK,"time =?",new String[]{ Long.toString(lastTime) });
		        					
					        		}
					        		
	    			    		}
	    			    		
	    			    		else{
	    			    			
	    			    			// Debug
		    			    		
		    			    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&ST&SS. Inicialización rCFBolus."
		    			    				+ " Error loading controller's states!");
		    			    		
		    			    		//
		    			    		
		    			    		Toast.makeText(IOMain.this, "Error loading HMS STATE ESTIMATE Table" , Toast.LENGTH_SHORT).show();
	    			    			
	    			    		}
				    		
				    		}
				        			
		        		}
		        		
		    		}
		        		
	        		// ************************************************************************************************************ //
		    		// ************************************************************************************************************ //
		    		
	        		// Rutina manejo de luces del semáforo
	        		
		    		// ************************************************************************************************************ //
	        		
	        		if (DIAS_STATE != State.DIAS_STATE_STOPPED && !basalCase){
			    		
			    		// El DiAs prende las luces en cualquier modo menos en Stop Mode
			    		
			    		// Debug
			    		
			    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&&SS. Actualización luces semáforo. Inicio");
			    		
			    		//
			    		
			    		// Obtengo el valor medio de glucosa de los últimos 30 min
			    		
			    		double gMean = Math.round(gController.getEstimator().getMean()); // Redondeo al entero más próximo
			    		
			    		// Genero un estimador con las últimas 3 muestras (15 min previos)
			    		
			    		Estimator estimator3M = new Estimator(3,gController.getSlqgController().getTs());
			    		double[][] cgmVector  = gController.getEstimator().getCgmVector().getData();
			    		
			    		for(int ii = 0; ii < 3; ++ii){
			    			estimator3M.insert(cgmVector[cgmVector.length-1-2+ii][0]);
			    		}
			    		
			    		// Hago una estimación a 10 min y capturo el trend y la predicción
			    		
			    		estimator3M.updatePred(10);
			    		double gTrend = Math.round(10.0*estimator3M.getTrend())/10.0; // Redondeo con un decimal
			    		double gPred  = Math.round(estimator3M.getPred()); // Redondeo al entero más próximo
			    		
			    		// ************************************************************************************************************ //
			    		// Rutina luces de hiperglucemia
			    		
			    		if(gMean>250.0 && gTrend>2.0 || gMean>350.0)
			    		{
			    			hyperLight = 2; // Enciendo luz roja
			    		}
			    		else if(gMean>180.0)
			    		{
			    			hyperLight = 1; // Enciendo luz amarilla
			    		}
			    		else
			    		{
			    			hyperLight = 0; // Enciendo luz verde
			    		}
			    		
			    		// ************************************************************************************************************ //
			    		// Rutina luces de hipoglucemia
			    		
			    		// Capturo la última muestra del vector de CGM
			    		
			    		double[][] cgmV = gController.getEstimator().getCgmVector().getData();
			    		double lastCgm  = cgmV[gController.getEstimator().getCgmVector().getM()-1][0]; 
			    		
			    		if(lastCgm<60.0)
			    		{
			    			hypoLight = 2; // Enciendo luz roja
			    		}
			    		else if(gPred>=70.0 && gPred<90.0)
			    		{
			    			if(gTrend<-1.0)
			    			{
				    			hypoLight  = 1; // Enciendo luz amarilla
			    			}
			    			else
			    			{
			    				hypoLight  = 0; // Enciendo luz verde
			    			}
			    		}
			    		else if(gPred<70.0)
			    		{
			    			if(gTrend<-1.0)
			    			{
				    			hypoLight  = 2; // Enciendo luz roja
			    			}
			    			else
			    			{
			    				hypoLight  = 1; // Enciendo luz amarilla
			    			}
			    		}
			    		else
			    		{
			    			hypoLight = 0; // Enciendo luz verde
			    		}
			    		
			    		// Debug
			    		
			    		log.debug("ARG /////// "+"DIAS_STATE_CL&OP&SS. Actualización luces semáforo. gMean: " + 
			    		gMean + ". gTrend: " + gTrend + ". gPred: " + gPred+". hypoLight: "+hypoLight+". hyperLight: "+hyperLight);
			    		
			    		//
			    			
	        		}
	        		
	        		// Si se activó el flag basalCase o estoy en Stop Mode, entonces apago las luces del semáforo 
	        		
	        		else
	        		{
	        			
	        			hypoLight  = 3;
		    			hyperLight = 3;
		    			
		    			if(basalCase)
		    			{
		    			
		    				// Debug
		    			
		    				log.debug("ARG /////// "+"DIAS_STATE_CL&OP&SS. Actualización luces semáforo. No hay info suficiente.");
		    			
		    				//
		    			
		    				Toast.makeText(IOMain.this, "Shutting down traffic lights: Lack of CGM info!" , Toast.LENGTH_SHORT).show();
		    				
		    			}
			    		
	        		}
		    		
	        		// ************************************************************************************************************ //
		    		// ************************************************************************************************************ //
		        		
	        		// Rutina para la detección del anuncio de comida o reseteo del controlador e infusión a lazo cerrado
	        		
		    		// ************************************************************************************************************ //
	        		
	        		// Tanto para la detección del anuncio de comida como para el cálculo de la infusión a lazo
	        		// cerrado el sistema tiene que estar en Closed Loop Mode y no haberse activado el flag basalCase
	        		// que indica la falta de información del CGM. Esto último no afecta a la detección de la comida
	        		// pero la detección de la comida es solo un instrumento para el cálculo a lazo cerrado.
	        		// Si el cálculo no puede hacer, no tiene sentido detectar el anuncio.
	        		
	        		// El flag basalCase se activaría en situaciones donde seguramente el sistema no puede estar a lazo
	        		// cerrado, pero, just in case, en el if se toman ambas condiciones
	        		
	        		// ************************************************************************************************************ //
	        		// Detección del anuncio de comida 
	        		
	        		// Si el sistema está en Closed Loop Mode y no se activó el flag basalCase se ejecuta
	        		// la rutina de detección de comida
	        		
	        		if (DIAS_STATE == State.DIAS_STATE_CLOSED_LOOP && !basalCase){
			        	
			        	// Debug
			    		
			    		log.debug("ARG /////// "+"DIAS_STATE_CLOSED_LOOP & !basalCase. "
			    				+ "Detección de anuncio de comida.");
			    		
			    		//
			    		
			        	lastTime         = 0;     // Última actualización de la tabla de anuncio
			        	int mealClass    = 1;     // Clase de comida
			        	boolean mealFlag = false; // Flag de anuncio
			        	int forCon       = 0;     // Flag para indicar si se forzó el reseteo
			        							  // No se declara boolean por cómo se termina guardando en la tabla
			        							  // 0: No se forzó, 1: Se forzó el reseteo
			        	
			        	// Puntero a la tabla de anuncio de comida
			        	
			        	Cursor cMeal = getContentResolver().query(Biometrics.USER_TABLE_3_URI, null, null, null, null);
			        	
			        	if (cMeal != null) {
			        		if (cMeal.moveToLast()) {
			        			
			        			lastTime  = cMeal.getLong(cMeal.getColumnIndex("time"));
					        	mealClass = (int)cMeal.getDouble(cMeal.getColumnIndex("d0"));
					        	forCon    = (int)cMeal.getDouble(cMeal.getColumnIndex("d1"));
					        	
					        	// Debug
	    			    		
	    			    		log.debug("ARG /////// "+"DIAS_STATE_CLOSED_LOOP. lastTime: "+lastTime+
	    			    				". mealClass: "+mealClass+". forCon: "+forCon);
	    			    		
	    			    		//

			        			if(Objects.equals(lastTime, null)){
			        				
			        				lastTime  = 0;
			        				mealClass = 1;
			        				
			        				// Debug
		    			    		
		    			    		log.debug("ARG /////// "+"DIAS_STATE_CLOSED_LOOP: Meal time null! --> Meal time = 0");
		    			    		
		    			    		//
		    			    		
			        			}
			        			
			        			else{
			        				
			        				if(mealClass==0){
			        					
						        		mealClass = 1;
						        		
						        	}
			        				
			        			}
			        		}
			        		
			        		else{
		        				
		        				// Debug
	    			    		
	    			    		log.debug("ARG /////// "+"DIAS_STATE_CLOSED_LOOP: Meal table empty! "
	    			    				+ "--> Meal time = 0");
	    			    		
	    			    		//	
	    			    		
			        		}
			        		
			        	}
			        	
			        	else{
			        		
			        		// Debug
				    		
				    		log.debug("ARG /////// "+"DIAS_STATE_CLOSED_LOOP: Error loading Meal table! "
				    				+ "--> Meal time = 0");
				    		
				    		//
				    	
			        	} 

			        	cMeal.close();
			        	
			        	timeDiff = currentTime - lastTime; // Diferencia temporal entre el tiempo actual y el último
			        									   // registro de la tabla de anuncio
			        	
			        	// Si se anunció una comida en la iteración anterior (máxima diferencia temporal de 300 s)
			        	// y no se forzó el reseteo del controlador activo el flag, sino no.
			        	
			        	// timeDiff>=0 se agrega por posibles errores en el grabado del tiempo que originen
			        	// diferencias temporales negativas y por ende que timeDiff<299 se cumpla, cuando
			        	// no debería.
			        	
			        	if(timeDiff < 299 && timeDiff>=0 && forCon!=1){
			        		
			        		mealFlag = true; // Activo el flag de anuncio de comida
			        		
			        	} 
			        	
			        	else{
			        		
			        		mealFlag = false; // No activo el flag de anuncio de comida
			        		
			        	}
			        	
			        	// Puntero a la tabla de anuncio de comida
			        	
			        	cMeal = getContentResolver().query(Biometrics.USER_TABLE_3_URI, null, 
			        			"l1>? AND d2!=?" , new String[]{Long.toString(currentTime-305),"0"}, null);
			        	
			        	int tEndAggIni = 0;
			        	
			        	if (cMeal != null) {
			        		if (cMeal.moveToLast()) {
			        			
					        	tEndAggIni = (int)cMeal.getDouble(cMeal.getColumnIndex("d2"));
					        	
					        	// Debug
	    			    		
	    			    		log.debug("ARG /////// "+"DIAS_STATE_CLOSED_LOOP. Actualización tEndAgg. "+
	    			    		"tEndAggIni: "+tEndAggIni);
	    			    		
	    			    		//
	    			    		
	    			    		Cursor cKStates = getContentResolver().query(Biometrics.HMS_STATE_ESTIMATE_URI, null, null, null, null);
	    			    		
	    			    		if (cKStates != null) {
					        		
					        		if (cKStates.moveToLast()) {
					        									        				
					        			lastTime = cKStates.getLong(cKStates.getColumnIndex("time"));
					        			
					        			// Debug
			    			    		
			    			    		log.debug("ARG /////// "+"DIAS_STATE_CLOSED_LOOP. Actualización tEndAgg. "
			    			    				+ "lastTime: "+lastTime);
			    			    		
			    			    		//
			    			    		
					        			if (Objects.equals(lastTime, null)){
					        				
					        				lastTime = currentTime;
					        				
					        				// Debug
				    			    		
				    			    		log.debug("ARG /////// "+"DIAS_STATE_CLOSED_LOOP. Actualización tEndAgg."
				    			    				+ " lastTime null --> currentTime");
				    			    		
				    			    		//
					        				
					        			}
					        			
					        		}
					        		
					        		else{
					        			
					        			lastTime = currentTime;
					        			
					        			// Debug
			    			    		
			    			    		log.debug("ARG /////// "+"DIAS_STATE_CLOSED_LOOP. Actualización tEndAgg."
			    			    				+ " moveToLast() null --> currentTime");
			    			    		
			    			    		//
					        			
					        		}
					        		
					        		if(lastTime == currentTime){
							        	
		        			    		ContentValues statesTableK = new ContentValues();
		        			    		
		        			    		statesTableK.put("time", currentTime);
		        			    		statesTableK.put("creditRequest", (double)tEndAggIni);

		        						getContentResolver().insert(Biometrics.HMS_STATE_ESTIMATE_URI, statesTableK);
		        						
					        		}
					        		
					        		else{
					        			
					        			ContentValues statesTableK = new ContentValues();
		        			    		
					        			statesTableK.put("creditRequest", (double)tEndAggIni);
		        			    		
		        						getContentResolver().update(Biometrics.HMS_STATE_ESTIMATE_URI, statesTableK,"time =?",new String[]{ Long.toString(lastTime) });
		        					
					        		}
					        		
	    			    		}
	    			    		
	    			    		else{
	    			    			
	    			    			// Debug
		    			    		
		    			    		log.debug("ARG /////// "+"DIAS_STATE_CLOSED_LOOP. Actualización tEndAgg."
		    			    				+ " Error loading controller's states!");
		    			    		
		    			    		//
		    			    		
		    			    		Toast.makeText(IOMain.this, "Error loading HMS STATE ESTIMATE Table" , Toast.LENGTH_SHORT).show();
	    			    			
	    			    		}
			        			
			        		}
			        		
			        		else{
		        				
		        				// Debug
	    			    		
	    			    		log.debug("ARG /////// "+"DIAS_STATE_CLOSED_LOOP. Actualización tEndAgg. No meal bolus detected! ");
	    			    		
	    			    		//	
	    			    		
			        		}
			        		
			        	}
			        	
			        	else{
			        		
			        		// Debug
				    		
				    		log.debug("ARG /////// "+"DIAS_STATE_CLOSED_LOOP. Actualización tEndAgg. Error loading Meal table! ");
				    		
				    		//
				    		
				    		Toast.makeText(IOMain.this, "Error loading Meal table!" , Toast.LENGTH_SHORT).show();
				    	
			        	} 

			        	cMeal.close();
		    		
		    		// ************************************************************************************************************ //
		    		// ************************************************************************************************************ //
		    		// Rutina para cargar los estados y variables del controlador
		    		
			    		lastTime             = 0; // Acá voy a capturar el tiempo de la última actualización de estados del SLQG
			    		long diffKStatesTime = 0; // Variable para capturar la diferencia temporal entre el tiempo actual y el último asociado
			    		                          // a la actualización de estados del controlador
			    		
			    		// Puntero a la tabla del controlador
			    		
			    		Cursor cKStates = getContentResolver().query(Biometrics.HMS_STATE_ESTIMATE_URI, null, null, null, null); 
			    		
			        	if (cKStates != null) {
			        		
			        		if (cKStates.moveToLast()) {
			        			
			        			// rCFBolus y tEndAgg son los contadores asociados a los BACs
		        				
		        				// Si hay registros guardados los capturo, luego los actualizo al tiempo actual
			        			
			        			lastTime     = cKStates.getLong(cKStates.getColumnIndex("time"));
			        			int rCFBolus = (int)cKStates.getDouble(cKStates.getColumnIndex("correction_in_units"));
			        			int tEndAgg  = (int)cKStates.getDouble(cKStates.getColumnIndex("creditRequest"));
	        					
			        			if(Objects.equals(lastTime, null)){
			        				
			        				// Si el puntero devuelve null entonces es probable que no haya ningún registro en la tabla. Podría ocurrir que haya, pero
			        				// que se haya encendido luego de mucho tiempo, en ese caso la instancia gController tiene los valores por defecto igualmente
			        				
			        				// Debug
		    			    		
		    			    		log.debug("ARG /////// "+"DIAS_STATE_CLOSED_LOOP: Controller's states time null! --> x and variables = 0");
		    			    		
		    			    		//
		    			    		
			        			}
			        			
			        			else{
			        				
			        				diffKStatesTime = currentTime - lastTime;
			        				
			        				if(Objects.equals(rCFBolus, null)){
			        					gController.setrCFBolus(0);
			        				}
			        				else{
			        					gController.setrCFBolus(rCFBolus);
			        				}
			        				
			        				if(Objects.equals(tEndAgg, null)){
			        					gController.settEndAgg(0);
			        				}
			        				else{
			        					gController.settEndAgg(tEndAgg);
			        				}
			        				
			        				// Debug
		    			    		
		    			    		log.debug("ARG /////// "+"DIAS_STATE_CLOSED_LOOP: Updating controller's states. diffKStatesTime: " +
		    			    		diffKStatesTime+". rCFBolus: "+gController.getrCFBolus()+". tEndAgg: "+gController.gettEndAgg());
		    			    		
		    			    		//
		    			    		
		    			    		// Actualizo los contadores BACs al tiempo actual
		    			    		
		    			    		int nIter = (int)Math.round(diffKStatesTime/300.0);
		    			    		
		    			    		for(int ii = 0; ii < nIter-1; ++ii){
		    			    			gController.getgControllerState().updateBolusVar(); // rCFBolus y tEndAgg
		    			    		}
		    			    		
		    			    		// Tolero una desconexión del modo Closed-Loop de 7.5 min máx, sino las variables se reinician
		    			    		
		    			    		// diffKStates es 0 cuando se inicializa el vector de CGM y se define un rCFBolus inicial, sin registros
		    			    		// en la tabla del controlador
		    			    		
			        				if(diffKStatesTime<455 && diffKStatesTime!=0){	
			        					
			        					// Debug
			    			    		
			    			    		log.debug("ARG /////// "+"DIAS_STATE_CLOSED_LOOP: Updating controller's states. diffKStatesTime <455 s");
			    			    		
			    			    		//
			    			    		
			    			    		// Chequeo si durante la última ejecución en Closed-Loop se pulsó el botón de Reset
			    			    		
			    			    		long rTime = 0;
			    			    		int rFlag  = 0;
			    			    		lastTime   = 0;
			    			    		
			    			    		Cursor cReset = getContentResolver().query(Biometrics.USER_TABLE_3_URI, null, null, null, null);
							        	if (cReset != null) {
							        		if (cReset.moveToLast()) {
							        			
							        			rTime    = cReset.getLong(cReset.getColumnIndex("time")); // Tiempo en que se forzó el modo conservador o se anunció una comida
							        			rFlag    = (int)cReset.getDouble(cReset.getColumnIndex("d1")); // Flag para forzar el modo conservador
							        			lastTime = cReset.getLong(cReset.getColumnIndex("l0")); // Tiempo de la última sincronización previa al momento de actualizar la User Table 3
							        			
							        			if(Objects.equals(rTime, null)){
							        				
							        				rTime    = 0;
							        				rFlag    = 0;
							        				lastTime = 0;
							        				
							        				// Debug
						    			    		
						    			    		log.debug("ARG /////// "+"DIAS_STATE_CLOSED_LOOP: User table 3 time null! --> rTime = 0");
						    			    		
						    			    		//
						    			    		
							        			}
							        		}
							        		
							        		else{
							        			
							        			// Debug
					    			    		
					    			    		log.debug("ARG /////// "+"DIAS_STATE_CLOSED_LOOP: User table 3 table empty! --> rtime = 0");
					    			    		
					    			    		//	
					    			    		
							        		}
							        	}
							        	
							        	else{
							        		
							        		// Debug
				    			    		
				    			    		log.debug("ARG /////// "+"DIAS_STATE_CLOSED_LOOP: Error loading User table 3! --> rtime = 0");
				    			    		
				    			    		//	
				    			    		
				    			    		Toast.makeText(IOMain.this, "Checking Force Reset: Error loading User Table 3" , Toast.LENGTH_SHORT).show();
				    			    		
							        	}
							        	
							        	cReset.close();
							        	
							        	if(currentTime-rTime<305 && rFlag == 1){
							        		
							        		// Rutina de reseteo del controlador
							        		
							        		// Debug
				    			    		
				    			    		log.debug("ARG /////// "+"DIAS_STATE_CLOSED_LOOP: Reset command detected. Forcing conservative mode");
				    			    		
				    			    		//
				    			    		
				    			    		Toast.makeText(IOMain.this, "Reset command detected. Forcing conservative mode" , Toast.LENGTH_SHORT).show();
				    			    		
				        					gController.getSlqgController().settMeal(0);
				        					gController.getSlqgController().setExtAgg(0);	
				        					gController.getEstimator().setListening(0);
				        					gController.getEstimator().setMCount(0);
			        						gController.getSlqgController().setSLQGState(gController.getSlqgController().getConservativeState());
			        						gController.getSafe().setIOBMaxCF(0.0);
			        						gController.getSafe().setIobMax(gController.getSafe().getIobMaxSmall());
			        						gController.setpCBolus(0.0);
			        						
			        						ContentValues userTable = new ContentValues();
			        						userTable.put("time", rTime);
			        						userTable.put("l0", lastTime);
			        						userTable.put("l1", 0);	
			        						userTable.put("d0", 1.0);
			        						userTable.put("d1", 0.0); // Reset-flag is reseted
			        						userTable.put("d2", 0.0);
			        						userTable.put("d3", 0.0);
			        						userTable.put("d4", 0.0);	
			        						userTable.put("d5", 0.0);
			        						userTable.put("d6", 0.0);
			        						userTable.put("d7", 0.0);
			        						userTable.put("d8", 0.0);	
			        						userTable.put("d9", 0.0);
			        						userTable.put("d10", 0.0);
			        						userTable.put("d11", 0.0);
			        						userTable.put("d12", 0.0);
			        						userTable.put("d13", 0.0);
			        						userTable.put("d14", 0.0);
			        						userTable.put("d15", 0.0);
			        						userTable.put("send_attempts_server", 1);	
			        						userTable.put("received_server", true);
			        			    		getContentResolver().insert(Biometrics.USER_TABLE_3_URI, userTable);
			        			    		
							        	}
							        	
							        	else{
							        		
							        		// No se pulsó el botón de reset, por ende se cargan las variables guardadas
							        		
				        					int tMeal          = (int)cKStates.getDouble(cKStates.getColumnIndex("bolus_amount"));
				        					int extAgg         = (int)cKStates.getDouble(cKStates.getColumnIndex("MealBolusA"));
				        					int slqgStateFlag  = (int)cKStates.getDouble(cKStates.getColumnIndex("IOBrem"));
				        					int listening      = (int)cKStates.getDouble(cKStates.getColumnIndex("hmin"));
				        					int mCount         = (int)cKStates.getDouble(cKStates.getColumnIndex("Hmax"));
				        					double iobMaxCF    = cKStates.getDouble(cKStates.getColumnIndex("d"));
				        					double iobMax      = cKStates.getDouble(cKStates.getColumnIndex("CORRA"));
				        					double pCBolus     = cKStates.getDouble(cKStates.getColumnIndex("MealBolusArem"));
								        	
				        					gController.getSlqgController().settMeal(tMeal);
				        					gController.getSlqgController().setExtAgg(extAgg);
				        					gController.getEstimator().setListening(listening);
				        					gController.getEstimator().setMCount(mCount);
				        					gController.getSafe().setIOBMaxCF(iobMaxCF);
			        						gController.getSafe().setIobMax(iobMax);
			        						gController.setpCBolus(pCBolus);
			        						
				        					if(slqgStateFlag==1){
				        						
				        						gController.getSlqgController().setSLQGState(gController.getSlqgController().getAggressiveState());
				        						
				        					}
				        					
				        					// Debug
				    			    		
				    			    		log.debug("ARG /////// "+"DIAS_STATE_CLOSED_LOOP: Controller's variables loaded --- tMeal: " + tMeal
				    			    				+ ". extAgg: "+extAgg +". slqgStateFlag: "+slqgStateFlag+ ". mCount: "+mCount + ". listening: "+listening+". IOBMaxCF: "+iobMaxCF
				    			    				+ ". IobMax: "+iobMax + ". pCBolus: "+pCBolus);
				    			    		
				    			    		//
				    			    		
							        	}
						        					
						        	}
			        				
			        				else{
			        					
			        					// Si pasan más de 7.5 min en otro modo diferente a Closed-Loop, se ejecuta la rutina de reseteo de las variables
						        		
						        		// Debug
			    			    		
			    			    		log.debug("ARG /////// "+"DIAS_STATE_CLOSED_LOOP: More than 7.5 min from last CL update. Reinitilizating the controller variables");
			    			    		
			    			    		//
			    			    		
			    			    		Toast.makeText(IOMain.this, "More than 7.5 min from last CL update. Reinitilizating the controller variables" , Toast.LENGTH_SHORT).show();
			    			    		
			        					gController.getSlqgController().settMeal(0);
			        					gController.getSlqgController().setExtAgg(0);	
			        					gController.getEstimator().setListening(0);
			        					gController.getEstimator().setMCount(0);
		        						gController.getSlqgController().setSLQGState(gController.getSlqgController().getConservativeState());
		        						gController.getSafe().setIOBMaxCF(0.0);
		        						gController.getSafe().setIobMax(gController.getSafe().getIobMaxSmall());
		        						gController.setpCBolus(0.0);
					        			
			        				}
			        				
			        				Matrix kStates = new Matrix(13,1);
			        				
			        				// Los estados no se reinician cuando se pulsa Reset
			        				// Mantengo los estados por 12.5 min
			        				
			        				if(diffKStatesTime<755){
							        	
			        					double[][] kStatesAux = new double[13][1];
					        			
					        			kStatesAux[0][0]  = cKStates.getDouble(cKStates.getColumnIndex("IOB"));
					        			kStatesAux[1][0]  = cKStates.getDouble(cKStates.getColumnIndex("GPred"));
					        			kStatesAux[2][0]  = cKStates.getDouble(cKStates.getColumnIndex("GPred_correction"));
					        			kStatesAux[3][0]  = cKStates.getDouble(cKStates.getColumnIndex("Gpred_bolus"));
					        			kStatesAux[4][0]  = cKStates.getDouble(cKStates.getColumnIndex("Xi00"));
					        			kStatesAux[5][0]  = cKStates.getDouble(cKStates.getColumnIndex("Xi01"));
					        			kStatesAux[6][0]  = cKStates.getDouble(cKStates.getColumnIndex("Xi02"));
					        			kStatesAux[7][0]  = cKStates.getDouble(cKStates.getColumnIndex("Xi03"));
					        			kStatesAux[8][0]  = cKStates.getDouble(cKStates.getColumnIndex("Xi04"));
					        			kStatesAux[9][0]  = cKStates.getDouble(cKStates.getColumnIndex("Xi05"));
					        			kStatesAux[10][0] = cKStates.getDouble(cKStates.getColumnIndex("Xi06"));
					        			kStatesAux[11][0] = cKStates.getDouble(cKStates.getColumnIndex("Xi07"));
					        			kStatesAux[12][0] = cKStates.getDouble(cKStates.getColumnIndex("brakes_coeff"));
					        			
					        			kStates = new Matrix(kStatesAux);
					        			
			        				}
			        				
			        				else{
			        					
			        					// Debug
			    			    		
			    			    		log.debug("ARG /////// "+"DIAS_STATE_CLOSED_LOOP: More than 12.5 min from last CL update. Reinitilizating the controller states");
			    			    		
			    			    		//
			    			    		
			    			    		Toast.makeText(IOMain.this, "More than 12.5 min from last CL update. Reinitilizating the controller states" , Toast.LENGTH_SHORT).show();
			        					
			        				}
			        				
			        				gController.getSlqgController().getLqg().setX(kStates);
						        				
						        }

			        		}
			        		
			        		else{
		        				
		        				// Debug
	    			    		
	    			    		log.debug("ARG /////// "+"DIAS_STATE_CLOSED_LOOP: Controller's states table empty! --> x and variables = 0");
	    			    		
	    			    		//	
	    			    		
			        		}
			        		
			        	}
			        	
			        	else{
			        		
			        		// Debug
				    		
				    		log.debug("ARG /////// "+"DIAS_STATE_CLOSED_LOOP: Error loading controller's states! --> x and variables = 0");
				    		
				    		//
				    		
				    		Toast.makeText(IOMain.this, "Error loading HMS STATE ESTIMATE Table" , Toast.LENGTH_SHORT).show();
				    	
			        	}
			    		
			        	cKStates.close();
			        	
			        	// ************************************************************************************************************ //
			        	// ************************************************************************************************************ //
			        	
			        	// Acá se informa el bolo calculado
			        	
			    		// For corrections use the "correction" value (in Units)
						// For rate changes set "new_rate" to true and use "diff_rate" for the value (+/- subject's basal)
			    		
			        	// En esta aplicación diff_rate es el bolo completo, es decir que considera la basal del paciente. Para eso se modificó
			        	// el SSMservice en la forma correspondiente
			        	
			        	double uControl = 0.0;   	
			        	double[][] cgmV = gController.getEstimator().getCgmVector().getData();
			        	
			        	// Debug
			    		
			    		log.debug("ARG /////// "+"DIAS_STATE_CLOSED_LOOP. Controller State: " + gController.getgControllerState().toString()+ 
			    				". MealFlag: "+mealFlag +". MealClass: " + mealClass+". yCGM: " +cgmV[gController.getEstimator().getCgmVector().getM()-1][0]+". iobFactor: "+parameterIOBFactorF);
			    		
			    		//
			    				    			    		
			    		uControl = gController.run(mealFlag, mealClass, cgmV[gController.getEstimator().getCgmVector().getM()-1][0],parameterIOBFactorF); 
			    								        	
			    		// Insulin signal is divided into basal and correction channels
			    		// El bolo basal máximo es de 0.5 U de acuerdo a SysMan/Constraints
			    		// El máximo bolo de corrección es de 6 U
			    		// La máxima acción de control es por lo tanto de 6.5 U
			    		
			        	if(uControl>0.5){
			        		diff_rate  = 0.5;
			        		correction = uControl-diff_rate;
			        	}
			        	else{
			        		diff_rate  = uControl;
			        		correction = 0.0;
			        	}
			        	
			        	new_rate = true;
			        	
			        	// ************************************************************************************************************ //
			        	// ************************************************************************************************************ //
			        	
		        		// Guardo los estados y variables actualizadas del controlador
			        	
			    		ContentValues statesTableK = new ContentValues();
			    		double[][] xstates = gController.getSlqgController().getLqg().getX().getData();
			    		statesTableK.put("IOB", xstates[0][0]);
			    		statesTableK.put("Gpred", xstates[1][0]);
			    		statesTableK.put("Gpred_correction", xstates[2][0]);
			    		statesTableK.put("Gpred_bolus", xstates[3][0]);
			    		statesTableK.put("Xi00", xstates[4][0]);
			    		statesTableK.put("Xi01", xstates[5][0]);
			    		statesTableK.put("Xi02", xstates[6][0]);
			    		statesTableK.put("Xi03", xstates[7][0]);
			    		statesTableK.put("Xi04", xstates[8][0]);
			    		statesTableK.put("Xi05", xstates[9][0]);
			    		statesTableK.put("Xi06", xstates[10][0]);
			    		statesTableK.put("Xi07", xstates[11][0]);
			    		statesTableK.put("brakes_coeff", xstates[12][0]);
			    		statesTableK.put("time", currentTime);
			    		statesTableK.put("bolus_amount", (double)gController.getSlqgController().gettMeal());
			    		statesTableK.put("MealBolusA", (double)gController.getSlqgController().getExtAgg());
			    		statesTableK.put("MealBolusArem", gController.getpCBolus());
			    		statesTableK.put("CORRA", gController.getSafe().getIobMax());
			    		int slqgStateFlag = 0;
			    		if(Objects.equals(gController.getSlqgController().getSLQGState().getStateString(), "Aggressive")){
			    			slqgStateFlag = 1;
			    		}
			    		statesTableK.put("IOBrem", (double)slqgStateFlag);
			    		statesTableK.put("d", gController.getSafe().getIOBMaxCF());
			    		statesTableK.put("hmin", (double)gController.getEstimator().getListening());
			    		statesTableK.put("Hmax", (double)gController.getEstimator().getMCount());
			    		statesTableK.put("correction_in_units", (double)gController.getrCFBolus());
			    		statesTableK.put("creditRequest", (double)gController.gettEndAgg());
			    		
						getContentResolver().insert(Biometrics.HMS_STATE_ESTIMATE_URI, statesTableK);
				
	        		}
	        		
	        		// ************************************************************************************************************ //
		        	// ************************************************************************************************************ //

	        		// Ambos casos el DiAs está en Pump Mode o si el DiAs está en Closed Mode con el flag basalCase activado
	        		// implican que se infunde la insulina basal
	        		
	        		// El caso en que el DiAs está en Pump Mode se agrega aquí para actualizar el IOB correctamente
	        		
		        	if (DIAS_STATE == State.DIAS_STATE_OPEN_LOOP || (DIAS_STATE == State.DIAS_STATE_CLOSED_LOOP && basalCase)){
		        		
		        		// When the open-loop mode is selected the system does not take into account how much insulin 
		        		// the controller requested, so pCBolus should be 0 the first time.
		        		
		        		// gController.getDiasState()==2 cuando en la iteración anterior estaba en Closed Loop Mode
		        		
		        		if(gController.getDiasState()==2 && DIAS_STATE == State.DIAS_STATE_OPEN_LOOP){ 
		        			
		        			gController.setpCBolus(0.0);
		        			
		        			// Debug
				    		
				    		log.debug("ARG /////// "+"Transición de Closed-Loop a Pump Mode --> pCBolus = 0");
				    		
				    		//
		        			
		        		}
		        		
		        		double pcb    = gController.getpCBolus(); // Capturo el pCBolus
		        		double mBasal = 0.0; 					  // Bolo de infusión basal
		        		
		        		// Si estoy en Pump Mode no genero el bolo basal acá, pero tengo que actualizar el IOB
		        		// Para eso también tomo en cuenta si se definio un TBR
		        		
		        		if (DIAS_STATE == State.DIAS_STATE_OPEN_LOOP){
		        			
			        		int  perTBR        = 100; // Porcentaje por el que se multiplica el perfil de insulina basal
				    		long endTime       = 0;   // Fin del TBR
				    		long startTime     = 0;   // Inicio del TBR
				    		long actualEndTime = 0;   // Fin del TBR prematuro por el usuario
				    		
				    		// Puntero a la tabla de TBR
				    		
				    		Cursor bTime = getContentResolver().query(Biometrics.TEMP_BASAL_URI, null, null, null, null);
				    		
				        	if (bTime != null) {
				        		if (bTime.moveToLast()) {
				        			
				        			endTime       = bTime.getLong(bTime.getColumnIndex("scheduled_end_time"));
				        			startTime     = bTime.getLong(bTime.getColumnIndex("start_time"));
				        			actualEndTime = bTime.getLong(bTime.getColumnIndex("actual_end_time"));
				        			perTBR        = bTime.getInt(bTime.getColumnIndex("percent_of_profile_basal_rate"));
				        			
				        			// Debug
						    		
						    		log.debug("ARG /////// "+"DIAS_STATE_OPEN_LOOP. endTime: "+endTime+". StartTime: "+startTime+
						    				". actualEndTime: "+actualEndTime+". PerTBR: "+perTBR);
						    		
						    		//
						    		
				        			if(Objects.equals(startTime, null)){
				        				
				        				perTBR        = 100;
				        				actualEndTime = 0;
				        				startTime     = 0;
				        				endTime       = 0;
				        				
				        				// Debug
			    			    		
			    			    		log.debug("ARG /////// "+"DIAS_STATE_OPEN_LOOP. TBR start time null! --> Per = 100%");
			    			    		
			    			    		//
			    			    		
				        			}
				        			
				        		}
				        		
				        		else{
			        				
				        			// Debug
						    		
						    		log.debug("ARG /////// "+"DIAS_STATE_OPEN_LOOP. TBR table empty! --> Per = 100%");
						    		
						    		//
						    		
				        		}
				        		
				        	}
				        	
				        	else{
			    				
				        		// Debug
					    		
					    		log.debug("ARG /////// "+"DIAS_STATE_OPEN_LOOP. Error loading TBR table! --> Per = 100%");
					    		
					    		//
					    		
					    		Toast.makeText(IOMain.this, "Error loading TBR table!" , Toast.LENGTH_SHORT).show();
					    		
				        	}
				        	
				        	bTime.close();
				        	
				        	// ************************************************************************************************************ //
				        	
				        	// Si se forzó el fin del TBR antes de tiempo
				        	
				        	if (actualEndTime!=0){
				        		
				        		// Debug
					    		
					    		log.debug("ARG /////// "+"DIAS_STATE_OPEN_LOOP. actualEndTime!=0");
					    		
					    		//
					    		
				        		if (currentTime<=actualEndTime && currentTime>=startTime){
				        			
				        			mBasal = perTBR*gController.getPatient().getBasalU()/(12.0)/(100.0);
				        			
				        		}
				        		
				        		else{
				        			
				        			mBasal = gController.getPatient().getBasalU()/(12.0);
				        			
				        		}
				        		
				        	}
				        	
				        	// Si el actualEndTime es 0 solo comparo el tiempo actual 
				        	// con el de inicio y fin primero establecidos
				        	
				        	else if (currentTime<=endTime && currentTime>=startTime){
				        		
				        		mBasal = perTBR*gController.getPatient().getBasalU()/(12.0)/(100.0);
				        		
				        	}
				        	
				        	else{
				        		
				        		mBasal = gController.getPatient().getBasalU()/(12.0);
				        		
				        	}
				        	
				        	// Debug
				    		
				    		log.debug("ARG /////// DIAS_STATE_OPEN_LOOP. mBasal: "+mBasal + ". Subject basal bolus: "+gController.getPatient().getBasalU()/12.0 + ". perTBR: " + 
				    		perTBR + ". currentTime: " + currentTime + ". startTime: "+ startTime + ". endTime: "+endTime + ". actualEndTime: "+actualEndTime);
				    		
				    		//
				    		
		        		}
		        		
		        		// Si estoy en Closed Loop Mode con el flag basalCase activado
		        		// infundo la insulina basal
		        		
		        		else{
		        			
		        			mBasal = gController.getPatient().getBasalU()/(12.0);
		        			
		        			// Debug
				    		
				    		log.debug("ARG /////// DIAS_STATE_CLOSED_LOOP basalCase. mBasal: "+ mBasal + ". Subject basal bolus: "+gController.getPatient().getBasalU()/12);
				    		
				    		//
				    		
		        		}
			    		
		        		double mBasalF = new BigDecimal(Double.toString(mBasal)).setScale(16, RoundingMode.HALF_DOWN).doubleValue(); // Redondeo el mBasal
		        		
		        		// Debug
			    		
			    		log.debug("ARG /////// mBasalF: "+ mBasalF + ". Subject basal bolus: "+gController.getPatient().getBasalU()/12);
			    		
			    		//
			    		
			    		double basalBolus = gController.getPump().quantizeBolus(mBasalF+pcb); // Cuantizo el bolo
			    			    				    		
			    		double nPcb = new BigDecimal(Double.toString(mBasalF+pcb-basalBolus)).setScale(16, RoundingMode.HALF_DOWN).doubleValue(); // Genero el pCBolus
	        			
			    		gController.setpCBolus(nPcb);
	        			
			    		// Actualizo los estados del IOB
			    		
		        		double uAux      = 12.0*100.0*(basalBolus);
		        		double[][] uTemp = {{uAux/gController.getPatient().getWeight()}};
		    			Matrix u         = new Matrix(uTemp);
		    			
		        		for(int ii = 0; ii < gController.getSlqgController().getTs()/gController.getSafe().getTs(); ++ii){
		        			
			    			gController.getSafe().getIob().stateUpdate(u);  
			    			
		        		}
						
		        		// Si estoy en Closed Loop con el basalCase activado, además tengo que informar al SSMservice que 
		        		// tiene que infundir la insulina basal
		        		
						if (DIAS_STATE == State.DIAS_STATE_CLOSED_LOOP && basalCase){
							
							// El basalBolus no puede ser mayor que 0.5, pero por las dudas...
							
							if(basalBolus>0.5){
								
				        		diff_rate  = 0.5;
				        		correction = basalBolus-diff_rate;
				        		
				        	}
							
				        	else{
				        		
				        		diff_rate  = basalBolus;
				        		correction = 0.0;
				        		
				        	}
							
				        	new_rate = true;
							
							// Debug
				    		
				    		log.debug("ARG /////// DIAS_STATE_CLOSED_LOOP basalCase. "+"IOB's states updated. "
				    				+ "basalBolus: " + basalBolus + ". pcb: " + gController.getpCBolus() + ". uTemp: " + uTemp[0][0]);
				    		
				    		//	
				    		
						}
						
						else{
							
							// Debug
				    		
				    		log.debug("ARG /////// "+"DIAS_STATE_OPEN_LOOP. IOB's states updated. basalBolus: " + basalBolus
				    				+ ". pcb: " + gController.getpCBolus() + ". uTemp: " + uTemp[0][0]);
				    		
				    		//
				    		
						}
		        		
		        	}
		        	
		        	iobEst   = gController.getSafe().getIobEst(gController.getPatient().getWeight());
		        	iobBasal = gController.getSafe().getIobBasal(gController.getPatient().getBasalU(),gController.getPatient().getWeight());
		        	
		        	// Debug
		        	
		    		log.debug("ARG /////// DIAS_STATE_CL&OP&ST&SS. Final IOB: " + iobEst + ". IOB basal: " + iobBasal);
		    		
		    		//
		    		
		    		// ************************************************************************************************************ //
		        	// ************************************************************************************************************ //
		    		
		    		// Guardo los estados del IOB si estoy en Pump o Closed Loop Mode
		        		    			    		
		    		if(DIAS_STATE != State.DIAS_STATE_SENSOR_ONLY && DIAS_STATE != State.DIAS_STATE_STOPPED)
		    		{
			    		
		    			ContentValues statesTableIOB1 = new ContentValues();
			    		TableShortCut scTableIOB1     = new TableShortCut(); 
			    		double[][] iobStates1         = gController.getSafe().getIob().getX().getData();
			    		
			    		statesTableIOB1 = scTableIOB1.insertValue(statesTableIOB1, iobStates1[0][0], iobStates1[1][0], iobStates1[2][0], iobEst, 
			    				iobBasal, 0.0, 0.0, 0.0, 0.0, 
			    				0.0, 0.0, 0.0, 0.0);
			    		
						getContentResolver().insert(Biometrics.USER_TABLE_1_URI, statesTableIOB1);
						
						// Debug
			        	
			    		log.debug("ARG /////// DIAS_STATE_CL&OP. IOB states saved!");
			    		
			    		//
						
		    		}
		    		
		    		if(DIAS_STATE == State.DIAS_STATE_SENSOR_ONLY || DIAS_STATE == State.DIAS_STATE_STOPPED){
		    			gController.setpCBolus(0.0);
		    		}
		    		
		    		// ************************************************************************************************************ //
		        	// ************************************************************************************************************ //
		    		
		    	}
				
				else{
					
					// Debug
				
	        	
					log.debug("ARG /////// Error reading subject info!");
	    		
	    			//
	    		
	    			Toast.makeText(IOMain.this, "Error reading subject info!" , Toast.LENGTH_SHORT).show();
	    			
				}
				
			}
			
			else{
				
				// Debug
				
				log.debug("ARG /////// Asynchronous call!");
	    		
	    		//
				
			}
	    	
			gController.setDiasState(DIAS_STATE);
			
		}
		
		// All other modes...
		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		
		else{
			
			// Debug
			
			log.debug("ARG /////// "+"DIAS_STATE_UNKWOWN");
			
			//
			
		}
		
		// Build the message to respond to DIAS_SERVICE
		Message response = Message.obtain(null, Controllers.APC_PROCESSING_STATE_NORMAL, 0, 0);
		Bundle responseBundle = new Bundle();
		responseBundle.putBoolean("doesBolus", true);
		//responseBundle.putBoolean("doesRate", false);   /// *************************************** DEMIAN
	    responseBundle.putBoolean("doesRate", true);
		responseBundle.putDouble("recommended_bolus", correction);
		responseBundle.putBoolean("new_differential_rate", new_rate);
		responseBundle.putDouble("differential_basal_rate", diff_rate);
		responseBundle.putDouble("IOB", 0.0);
		responseBundle.putBoolean("asynchronous", asynchronous);
		responseBundle.putInt("stoplight",hypoLight);
		responseBundle.putInt("stoplight2",hyperLight);
		
		// Debug
		
		log.debug("ARG /////// "+"APC_SERVICE_CMD_CALCULATE_STATE: Respond to DiAs Service. Asynchronous: "+asynchronous+". Recommended_bolus: "+correction+ ". Diff rate: " + diff_rate);
		
		//
		    
		// Log the parameters for IO testing
		if (Params.getBoolean(getContentResolver(), "enableIO", false)) {
			Bundle b = new Bundle();
			b.putString(	"description", 
							" SRC:  APC"+
							" DEST: DIAS_SERVICE"+
							" -"+FUNC_TAG+"-"+
							" APC_PROCESSING_STATE_NORMAL"+
							" doesBolus="+responseBundle.getBoolean("doesBolus")+
							" doesRate="+responseBundle.getBoolean("doesRate")+
							" recommended_bolus="+responseBundle.getDouble("recommended_bolus")+
							" new_differential_rate="+responseBundle.getBoolean("new_differential_rate")+
							" differential_basal_rate="+responseBundle.getDouble("differential_basal_rate")+
							" IOB="+responseBundle.getDouble("IOB")
						);
			Event.addEvent(getApplicationContext(), Event.EVENT_SYSTEM_IO_TEST, Event.makeJsonString(b), Event.SET_LOG);
		}        			
		
		// Send response to DiAsService
		response.setData(responseBundle);
		sendResponse(response);
		break;
		*/
	}
}