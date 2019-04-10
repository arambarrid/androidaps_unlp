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
import info.nightscout.androidaps.db.Source;

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
import info.nightscout.androidaps.interfaces.Constraint;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.ConfigBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.NSClientInternal.data.NSSgv;
import info.nightscout.androidaps.plugins.Overview.OverviewPlugin;
import info.nightscout.androidaps.plugins.PumpCombo.ruffyscripter.history.Bolus;
import info.nightscout.androidaps.plugins.PumpCombo.ruffyscripter.history.PumpHistory;
import info.nightscout.androidaps.plugins.Treatments.Treatment;
import info.nightscout.androidaps.plugins.Treatments.TreatmentsPlugin;
import info.nightscout.androidaps.db.ExtendedBolus;
import info.nightscout.utils.DecimalFormatter;
import info.nightscout.utils.SP;
import info.nightscout.androidaps.plugins.ConfigBuilder.ConfigBuilderPlugin;

import info.nightscout.androidaps.plugins.PumpVirtual.VirtualPumpPlugin;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.db.ARGTable;
import info.nightscout.androidaps.db.BgReading;
import info.nightscout.androidaps.db.TemporaryBasal;
import info.nightscout.androidaps.data.Intervals;
import info.nightscout.androidaps.plugins.NSClientInternal.NSUpload;
import info.nightscout.utils.DateUtil;

import android.support.v4.app.FragmentManager;
import info.nightscout.androidaps.plugins.ARG.Dialogs.NewInitBolusDialog;

import info.nightscout.androidaps.plugins.ARG.ARGFragment;
// ************************************************************************************************************ //

public class IOMain{


    private static Logger log = LoggerFactory.getLogger(L.DATABASE);

	
	// TODO_AAPS : podemos cambiar despues el nombre
	enum State {
		DIAS_STATE_UNKWOWN,
		DIAS_STATE_CLOSED_LOOP,
		DIAS_STATE_OPEN_LOOP,
		DIAS_STATE_STOPPED,
		DIAS_STATE_SENSOR_ONLY
	}


	Profile profile;

	private int hypoLight  = 0;
	private int hyperLight = 0;

	// Power management
	/*

	private PowerManager pm;
	private PowerManager.WakeLock wl;
	
	
	private static final String TAG = "HMSservice";
	
    private Messenger mMessengerToClient = null;
    private final Messenger mMessengerFromClient = new Messenger(new IncomingHMSHandler());
    */

    // #####################################################################
    // ############ Variables pertenecientes al IOMain original ############
    // #####################################################################

	double correction = 0.0, diff_rate = 0.0;
	boolean new_rate = false;
	long nowMS;
	
	boolean asynchronous = false; 
	State DIAS_STATE = State.DIAS_STATE_UNKWOWN;

	double parameterIOBFactor;
	GController gController;
	Tvector subjectBasal;

	// Rutina 1
	double  delTotal     = 0.0;   // Variable para capturar cada uno de los posibles bolos asincrónicos
	int     statusIns    = 0;     // Variable que permite detectar si el bolo es el anunciado en la inicialización o fue dado con el DiAs 
	int     type         = 0;     // Variable equivalente a statusIns
	long    lastTime     = 0;     // Tiempo de la infusión del bolo
	long    currentTime  = 0; // Tiempo actual en segundos
	double  extraBolus   = 0.0;   // Bolos de corrección o de comida
	double  iobInitBolus = 0.0;   // Bolo de inicialización
	long    timeDiff     = 0;     // Diferencia entre el tiempo actual y el de la infusión
	boolean iobInitFlag  = false; // Flag para indicar que se debe ejecutar la rutina de inicialización de IOB
	
	// Rutina 2
	List<ARGTable> sBTime, cIob;
	ARGTable lastResult;
	long iobLastTime;
	double iobState1, iobState2, iobState3, iobEst;
	double iobBasal;
	Matrix iobState;
	

	// Rutina 4
	boolean basalCase   = false; 
	int     rCFBolusIni = 0; // Variable para retardar el posible BAC en la inicialización


	int mealClass    = 1;     // Clase de comida
	boolean mealFlag = false; // Flag de anuncio
	int forCon       = 0;     // Flag para indicar si se forzó el reseteo
							  // No se declara boolean por cómo se termina guardando en la tabla
							  // 0: No se forzó, 1: Se forzó el reseteo

    // #####################################################################
    // ############ Variables pertenecientes al IOMain original ############
    // #####################################################################

    // Variables de control en rutinas
    long lastTimeCGM_get = 0;
    long lastTimeInsulin_get = 0;
    long lastTimeTempBasal_get = 0;
    long lastEjectuarCada5Min_tick = -1;

    IOMain(){
    	log.debug("[ARGPLUGIN] IOMain instanciada.");
    }

    private void CGM_URI_Clone(){
	    // #########################################################################################################
	    // Biometrics.CGM_URI
	    // #########################################################################################################
    	// Campos
    	// cgm 			Valor de CGM
    	// time 		Tiempo realizado de la muestra
    	// state 		No se muy bien que significa, en 1 = error, 10=, 5=warmup

    	// Vector de DiAS que tiene las medidas de CGM, trato de
    	// simular su contenido 

	    long fromTime = 0, now = System.currentTimeMillis() ;
    	List<BgReading> bgData;
    	ARGTable cgm_uri_argTable;
    	int added = 0;

    	// Primera vez
    	if (lastTimeCGM_get == 0){
    		// Obtengo lecturas de como mucho hace 6 horas
    		fromTime = now - 6*3600*1000L;

    		// Verifico cual fue la ultima medida almacenada en el CGM_URI
    		List<ARGTable> cgm_uri_list = MainApp.getDbHelper()
    				.getAllARGTableFromTimeByDiASType("Biometrics.CGM_URI", fromTime, false);

    		if (cgm_uri_list.size() > 0){
	    		cgm_uri_argTable = cgm_uri_list.get(0);
	    		lastTimeCGM_get = cgm_uri_argTable.getLong("time") * 1000;

	    		// Entonces a partir de esta ultima medida es que consulto
		    	bgData = MainApp.getDbHelper().getBgreadingsDataFromTime(lastTimeCGM_get, true);

	    	}else{
	    		// no hay medidas desde ese tiempo en CGM_URI, chequeamos en AAPS
		    	bgData = MainApp.getDbHelper().getBgreadingsDataFromTime(fromTime, false);

	    	}
    	}else{
		    bgData = MainApp.getDbHelper().getBgreadingsDataFromTime(lastTimeCGM_get, false);

    	}

    	// hay nuevas medidas?
    	if (bgData.size() > 0){
		    // esta ordenado desde la ultima medida (0) hasta la mas antigua (N)
    		for (int i = 0;i < bgData.size(); i++){
    			JSONObject cgm_uri_json = new JSONObject();
    			try{
	    			cgm_uri_json.put("time", bgData.get(i).date/1000);
	    			cgm_uri_json.put("cgm", bgData.get(i).value);
	    			cgm_uri_json.put("state", 0);

					cgm_uri_argTable = new ARGTable(bgData.get(i).date, "Biometrics.CGM_URI", cgm_uri_json);
			        MainApp.getDbHelper().createARGTableIfNotExists(cgm_uri_argTable, "CGM_URI_Clone()");
					NSUpload.uploadARGTable(cgm_uri_argTable);

			        added++;
    			}catch(JSONException e){

    			}


    		}

    	}

    	log.debug("[ARGPLUGIN] CGM_URI : " + String.valueOf(added) + " added, prevlastTime: " + String.valueOf(lastTimeCGM_get) + " currentLastTime: " + String.valueOf(now));
	    lastTimeCGM_get = now;
    }

    public void INSULIN_URI_Clone(){
	    // #########################################################################################################
	    // Biometrics.INSULIN_URI
	    // #########################################################################################################
    	// Campos
    	// deliv_time 		cuando fue infundido ?
    	// deliv_total 		cantidad del bolo (double)
    	// status 			2=se_infundió_correctamente
    	// type 			2=bolo_correccion
    	// req_time			calculo que será cuando se solicitó

    	long prevlastTime = lastTimeInsulin_get, newLastTime = -1;
      	long fromTime = 0, now = System.currentTimeMillis() ;
    	List<Treatment> treatments;
    	ARGTable insulin_uri_argTable;
    	int added = 0;
    	boolean virtualPumpEnable = false;

    	if (ConfigBuilderPlugin.getPlugin().getActivePump() == 
    		VirtualPumpPlugin.getPlugin()){
    		virtualPumpEnable = true;
    	}

    	// Primera vez
    	if (lastTimeInsulin_get == 0){
    		// Voy a copiar lecturas de como mucho hace 6 horas
    		fromTime = now - 6*3600*1000L;

    		// Verifico cual fue la ultima medida almacenada en el INSULIN_URI
    		List<ARGTable> insulin_uri_list = MainApp.getDbHelper()
    				.getAllARGTableFromTimeByDiASType("Biometrics.INSULIN_URI", fromTime, false);

    		if (insulin_uri_list.size() > 0){
        		for (int tx = 0; tx < insulin_uri_list.size(); tx++){
		    		insulin_uri_argTable = insulin_uri_list.get(tx);
		    		long time =  insulin_uri_argTable.getLong("time_ms");
		    		if (time > lastTimeInsulin_get)
		    			lastTimeInsulin_get = time;
        		}
	    	}else{	
	    		lastTimeCGM_get = fromTime;
	    	}
    	}

      	treatments = TreatmentsPlugin.getPlugin().getTreatmentsFromHistory();

        for (int tx = 0; tx < treatments.size(); tx++) {
            Treatment t = treatments.get(tx);
            if (t.date > lastTimeInsulin_get)
            {
            	if (t.isValid && (t.source == Source.PUMP || virtualPumpEnable)){ // ESTO NO SE SI ES NECESARIO PERO POR LAS DUDAS
	    			JSONObject insulin_uri_json = new JSONObject();
	    			try{
						insulin_uri_json.put("time", t.date/1000);
						insulin_uri_json.put("type", 2); // 2 : tipo de bolo: de correccion
						insulin_uri_json.put("status", 2); // 2 es que fue totalmente infundida
						insulin_uri_json.put("deliv_total", t.insulin); // cantidad de insulina
						insulin_uri_json.put("deliv_time", t.date/1000);
						insulin_uri_json.put("req_time", t.date/1000);
						insulin_uri_json.put("time_ms", t.date);

						insulin_uri_argTable = new ARGTable(t.date, "Biometrics.INSULIN_URI", insulin_uri_json);
				        MainApp.getDbHelper().createARGTableIfNotExists(insulin_uri_argTable, "INSULIN_URI_Clone()");
						NSUpload.uploadARGTable(insulin_uri_argTable);

				        log.debug("[ARGPLUGIN] Insulina clonada (lastTimeInsulin_get-t.date = " + 
            					(t.date - lastTimeInsulin_get) + " ms) source: " 
				        		+ t.source + " time:" + t.date + " insulin:"
				        		 + t.insulin + " isValid:" + t.isValid 
				        		 + " lastTimeInsulin_get= " + lastTimeInsulin_get);
				        added++;

				        if (t.date > newLastTime)
				        	newLastTime = t.date;
	    			}catch(JSONException e){

	    			}
	    		}else{
            		log.debug("[ARGPLUGIN] Insulina ignorada, es invalida o no proviene de la bomba - source: " 
			        		+ t.source + " time:" + t.date + " insulin:"
			        		 + t.insulin + " isValid:" + t.isValid
				        		 + " lastTimeInsulin_get= " + lastTimeInsulin_get);
	    		}
            }else{
            	log.debug("[ARGPLUGIN] Insulina ignorada es mas vieja de lo solicitado (lastTimeInsulin_get-t.date = " + 
            			(lastTimeInsulin_get - t.date) + " ms) - source: " 
			        		+ t.source + " time:" + t.date + " insulin:"
			        		 + t.insulin + " isValid:" + t.isValid
				        		 + " lastTimeInsulin_get= " + lastTimeInsulin_get);
			        
            }


        }

        if (newLastTime > lastTimeInsulin_get)
        	lastTimeInsulin_get = newLastTime;

    	log.debug("[ARGPLUGIN] INSULIN_URI : " + String.valueOf(added) +
    			 " added, prevlastTime: " + String.valueOf(prevlastTime) +
    			 " currentLastTime: " + String.valueOf(lastTimeInsulin_get));

    }

    private void TEMP_BASAL_URI_Clone(){
	    // #########################################################################################################
	    // Biometrics.TEMP_BASAL_URI
	    // #########################################################################################################
    	// Campos
    	// start_time							Inicio del TBR
    	// percent_of_profile_basal_rate		Porcentaje por el que se multiplica el perfil de insulina basal
    	// scheduled_end_time 					Fin del TBR
    	// actual_end_time						Fin del TBR prematuro por el usuario

    	long prevlastTime = lastTimeTempBasal_get, newLastTime = -1;
      	long fromTime = 0, now = System.currentTimeMillis() ;
    	ARGTable tbr_uri_argTable;
        Intervals<TemporaryBasal> tempBasalList;
    	int added = 0;
    	boolean virtualPumpEnable = false;

    	if (ConfigBuilderPlugin.getPlugin().getActivePump() == 
    		VirtualPumpPlugin.getPlugin()){
    		virtualPumpEnable = true;
    	}

    	tempBasalList = TreatmentsPlugin.getPlugin().getTemporaryBasalsFromHistory();

		// Primera vez
    	if (lastTimeTempBasal_get == 0){
    		// Voy a copiar tbrs de como mucho hace 6 horas
    		fromTime = now - 6*3600*1000L;

    		// Verifico cual fue el ultimo TBR almacenado en el TEMP_BASAL_URI
    		List<ARGTable> tbr_uri_list = MainApp.getDbHelper()
    				.getAllARGTableFromTimeByDiASType("Biometrics.TEMP_BASAL_URI", fromTime, false);

    		if (tbr_uri_list.size() > 0){
        		for (int tx = 0; tx < tbr_uri_list.size(); tx++){
		    		tbr_uri_argTable = tbr_uri_list.get(tx);
		    		long time =  tbr_uri_argTable.getLong("time_ms");
		    		if (time > lastTimeTempBasal_get)
		    			lastTimeTempBasal_get = time;
        		}
	    	}else{	
	    		lastTimeTempBasal_get = fromTime;
	    	}
    	}

    	log.debug("[ARGPLUGIN] TBRs procesando " + tempBasalList.size() + " resultados.");

        for (int tx = 0; tx < tempBasalList.size(); tx++) {
            TemporaryBasal tbr = tempBasalList.get(tx);
            if (tbr.date > lastTimeTempBasal_get)
            {
            	if ( 
            			(tbr.source == Source.PUMP || virtualPumpEnable) &&
            	   		!tbr.isAbsolute  // Solo leo tbrs relativos
            	   )
            	{
            	   	
            		// Comentar esta linea si queremos que registre lo que están actualmente dándose
	                if (!tbr.isInProgress()) {
	                	if (tbr.durationInMinutes > 0){
		                	JSONObject tbr_uri_json = new JSONObject();
			    			try{
								tbr_uri_json.put("time", tbr.date/1000);

								tbr_uri_json.put("start_time", tbr.date/1000);
								tbr_uri_json.put("percent_of_profile_basal_rate", tbr.percentRate);
								tbr_uri_json.put("scheduled_end_time", (tbr.date/1000) + (tbr.durationInMinutes*60));

								// Cero significa que se está dando en este momento 
								tbr_uri_json.put("actual_end_time",(tbr.date/1000) + (tbr.getRealDuration() * 60));
								// Cuando se actualice porque aún no se está dando
								// 	(tbr.date/1000) + (tbr.getRealDuration() * 60)

								tbr_uri_json.put("time_ms", tbr.date);

								tbr_uri_argTable = new ARGTable(tbr.date, "Biometrics.TEMP_BASAL_URI", tbr_uri_json);
						        MainApp.getDbHelper().createARGTableIfNotExists(tbr_uri_argTable, "TEMP_BASAL_Clone()");
								NSUpload.uploadARGTable(tbr_uri_argTable);

						        log.debug("[ARGPLUGIN] TBR clonado (lastTimeTempBasal_get-tbr.date = " + 
		            					(tbr.date - lastTimeTempBasal_get) + " ms) source: " 
						        		+ tbr.source + " time:" + tbr.date  + " percent " + tbr.percentRate + "% " + 
						        		" idealmente termina: " + (tbr.date/1000) + (tbr.durationInMinutes*60) + 
						        		" duracion ideal: " + tbr.durationInMinutes + " min " + 
						        		" termino: " + (tbr.date/1000) + (tbr.getRealDuration() * 60) + 
						        		" realmente duró: " + tbr.getRealDuration() + " min " + 
						        		" lastTimeTempBasal_get= " + lastTimeTempBasal_get);
						        added++;

						        if (tbr.date > newLastTime)
						        	newLastTime = tbr.date;
			    			}catch(JSONException e){

			    			}
			    		}else{

	            	       log.debug("[ARGPLUGIN] TBR ignorado (tiempo nulo) (lastTimeTempBasal_get-tbr.date = " + 
	        					(tbr.date - lastTimeTempBasal_get) + " ms) source: " 
				        		+ tbr.source + " time:" + tbr.date  + " percent " + tbr.percentRate + "% " + 
				        		" idealmente termina: " + (tbr.date/1000) + (tbr.durationInMinutes*60) + 
				        		" duracion ideal: " + tbr.durationInMinutes + " min " + 
				        		" termino: " +  ((tbr.date/1000) + (tbr.getRealDuration() * 60))  + 
				        		" va durando: " + tbr.getRealDuration() + " min " +  
				        		" lastTimeTempBasal_get= " + lastTimeTempBasal_get);
			    		}
	                }else{
            	       log.debug("[ARGPLUGIN] TBR ignorado en progreso (lastTimeTempBasal_get-tbr.date = " + 
        					(tbr.date - lastTimeTempBasal_get) + " ms) source: " 
			        		+ tbr.source + " time:" + tbr.date  + " percent " + tbr.percentRate + "% " + 
			        		" idealmente termina: " + (tbr.date/1000) + (tbr.durationInMinutes*60) + 
			        		" duracion ideal: " + tbr.durationInMinutes + " min " + 
			        		" termino: " +  ((tbr.date/1000) + (tbr.getRealDuration() * 60))  + 
			        		" va durando: " + tbr.getRealDuration() + " min " +  
			        		" lastTimeTempBasal_get= " + lastTimeTempBasal_get);
	                }

            	}else{
       				log.debug("[ARGPLUGIN] TBR ignorado fuente no bomba (lastTimeTempBasal_get-tbr.date = " + 
	            					(tbr.date - lastTimeTempBasal_get) + " ms) source: " 
					        		+ tbr.source + " time:" + tbr.date  + " percent " + tbr.percentRate + "% " + 
					        		" idealmente termina: " + (tbr.date/1000) + (tbr.durationInMinutes*60) + 
					        		" duracion ideal: " + tbr.durationInMinutes + " min " + 
					        		" termino: " +  ((tbr.date/1000) + (tbr.getRealDuration() * 60)) +
					        		" realmente duró: " + tbr.getRealDuration() +  " min " + 
					        		" lastTimeTempBasal_get= " + lastTimeTempBasal_get);
            	}
            }else{
    	       log.debug("[ARGPLUGIN] TBR ignorado (lastTimeTempBasal_get-tbr.date = " + 
        					(tbr.date - lastTimeTempBasal_get) + " ms) source: " 
			        		+ tbr.source + " time:" + tbr.date  + " percent " + tbr.percentRate + "% " + 
			        		" idealmente termina: " + (tbr.date/1000) + (tbr.durationInMinutes*60) + 
			        		" duracion ideal: " + tbr.durationInMinutes + " min " + 
			        		" termino: " + ((tbr.date/1000) + (tbr.getRealDuration() * 60)) + 
			        		" realmente duró: " + tbr.getRealDuration() +  " min " + 
			        		" lastTimeTempBasal_get= " + lastTimeTempBasal_get);
            }
        }

        if (newLastTime > lastTimeTempBasal_get)
        	lastTimeTempBasal_get = newLastTime;

    	log.debug("[ARGPLUGIN] TEMP_BASAL_URI : " + String.valueOf(added) +
    			 " added, prevlastTime: " + String.valueOf(prevlastTime) +
    			 " currentLastTime: " + String.valueOf(lastTimeInsulin_get));
    }

    public void AAPStoDiAS(){
	    // Controladas por el IOMain.java
	    // Biometrics.USER_TABLE_1_URI
	    // Biometrics.USER_TABLE_3_URI
	    // Biometrics.USER_TABLE_4_URI
	    // Biometrics.HMS_STATE_ESTIMATE_URI


	    // Controladas por el DiAS

	    // Biometrics.CGM_URI
	    this.CGM_URI_Clone();
	    
	    // Biometrics.INSULIN_URI

	    // TODO_APS: Tiene que ser asincronico
	     this.INSULIN_URI_Clone();

	    // Biometrics.TEMP_BASAL_URI
	    this.TEMP_BASAL_URI_Clone();

    }

    private void emitToastMsg(String msg){

		log.debug("[ARGPLUGIN:IOMAIN] ### Toast.makeText ### " + msg);

    }

    private void rutina_1_capturar_bolos_asincronicos(){
    	// ************************************************************************************************************ //
		// ************************************************************************************************************ //
		
		// Rutina para capturar bolos asincrónicos para actualizar el IOB
		
		// *********************************************************************************************************** /// 
		
		log.debug("[ARGPLUGIN:IOMAIN] ### Rutina 1 : Capturar Bolos Asincronicos ###");

		delTotal     = 0.0;   // Variable para capturar cada uno de los posibles bolos asincrónicos
		statusIns    = 0;     // Variable que permite detectar si el bolo es el anunciado en la inicialización o fue dado con el DiAs 
		type         = 0;     // Variable equivalente a statusIns
		lastTime     = 0;     // Tiempo de la infusión del bolo
		currentTime  = System.currentTimeMillis()/1000; // Tiempo actual en segundos
		extraBolus   = 0.0;   // Bolos de corrección o de comida
    	iobInitBolus = 0.0;   // Bolo de inicialización
    	timeDiff     = 0;     // Diferencia entre el tiempo actual y el de la infusión
    	iobInitFlag  = false; // Flag para indicar que se debe ejecutar la rutina de inicialización de IOB

		List<ARGTable> aTime = INSULIN_URI_query_deliv_time(currentTime-299);

		if (aTime.size() > 0) {// if (aTime != null) {
			log.debug("[ARGPLUGIN:IOMAIN]     -> : aTime.size() > 0");
		
    		for (ARGTable item : aTime) { //while(aTime.moveToNext()){
    			
	        	lastTime = item.getLong("deliv_time");
	        	delTotal = item.getDouble("deliv_total");
	        	statusIns = item.getInt("status");
	        	type = item.getInt("type");

	        	// TODO_APS: este caso no va a darse ¡REVISAR!
	        	// o SI, en caso de agregarse una tabla de insulin 
	        	// que no contenga ese campo
	        	if(Objects.equals(lastTime, null)){
	        		
	        		// Si son nulls los seteo en 0
	        		
    				lastTime  = 0;
    				delTotal  = 0.0;
    				statusIns = 0;
    				type      = 0;
				
    				// Debug
    				
    				log.debug("[ARGPLUGIN:IOMAIN]     -> : Captura de bolos asincrónicos. Last insulin deliv time null! --> Ins deliv time = 0");
    				
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
			        	
		        	log.debug("[ARGPLUGIN:IOMAIN]     -> : Captura de bolos asincrónicos. lastTime: " + lastTime + ". delTotal: " + delTotal + ". statusIns: " + statusIns + ". type: " + type);
		        	
		        	// 
	        	}
	        	
    		}
    		
		}else{
			log.debug("[ARGPLUGIN:IOMAIN]     -> : aTime.size() == 0");
    	}
		
		log.debug("[ARGPLUGIN:IOMAIN]     -> extraBolus: " + extraBolus + ". iobInitBolus: " + iobInitBolus);

    }

    private void rutina_2_correccion_iob_bolos_sincronicos_no_infundidos(){
		// *********************************************************************************************************** /// 
		// ************************************************************************************************************ //
		// Rutina corrección IOB por bolos sincrónicos no infundidos
		// *********************************************************************************************************** /// 
		// ************************************************************************************************************ //
		
		log.debug("[ARGPLUGIN:IOMAIN] ### Rutina 2 : Correcion IOB - Bolos Sincronicos No Infundidos ###");

		// Puntero al último bolo de insulina sincrónico
		sBTime = INSULIN_URI_query_reqtime_and_type(currentTime-305, 2);

		// Puntero al último resultado del controlador
		lastResult = ARG_RESULT_query(currentTime-305);

		//CASO 1: Hay resultado y hay bolo
		if((lastResult!=null) && (sBTime.size()>0)){
			//Chequeo si es la misma cantidad de insulina en ambos
			if((lastResult.getDouble("bolus")) == (sBTime.get(0).getDouble("deliv_total"))){
				log.debug("[ARGPLUGIN:IOMAIN] ### Rutina 2 :-> : CASO 1: El bolo se infundió correctamente");
			}
			//TODO_AAPS: si no coincide la cantida de insulina habría que ver cómo resolverlo pero es un caso raro.
		}
		else {
			//CASO 2: No hay resultado ni bolo. Significa que no se corrió el controlador
			if ((lastResult == null) && (sBTime.size() == 0)) {
				log.debug("[ARGPLUGIN:IOMAIN] ### Rutina 2 :-> : CASO 2: No hay resultados ");
			} else
				//CASO 3: No hay bolo y hay resultado. Se debe actualizar el IOB.
				if (sBTime.size() == 0) {
					//CASO 3A: Si el resultado es cero significa que no se inyectó por decisión del controlador
					if(lastResult.getDouble("bolus")==0)
						log.debug("[ARGPLUGIN:IOMAIN] ### Rutina 2 :-> : CASO 3A: El resultado del controlador fue 0");
					else {
						// CASO 3B: Si el resultado es distinto de cero significa que no se comunicó con la bomba
						log.debug("[ARGPLUGIN:IOMAIN] ### Rutina 2 :-> : CASO 3B: PROBLEMA: No se inyectó.");
					}


					// Puntero a la tabla de IOB
					cIob = MainApp.getDbHelper().getLastsARGTable("ARG_IOB_STATES", 2);

					if (cIob.size() > 0) { //(cIob != null) {

						iobLastTime = cIob.get(0).getLong("iobLastTime");
						iobState1 = cIob.get(0).getDouble("iobStates0");
						iobState2 = cIob.get(0).getDouble("iobStates1");
						iobState3 = cIob.get(0).getDouble("iobStates2");
						iobEst = cIob.get(0).getDouble("iobEst");
						iobBasal = cIob.get(0).getDouble("iobBasal");


						log.debug("[ARGPLUGIN:IOMAIN] 	  -> : cIob.size() > 0 - "
								+ "Estados últimos. iobLastTime: " + iobLastTime +
								". iobState1: " + iobState1 + ". iobState2: " + iobState2 +
								". iobState3: " + iobState3 + ". iobBasal: " + iobBasal +
								"iobEst: " + iobEst);

						// Voy a la anteúltima fila
						if (cIob.size() > 1) { // (cIob.moveToPrevious()){
							iobState1 = cIob.get(1).getDouble("iobStates0");
							iobState2 = cIob.get(1).getDouble("iobStates1");
							iobState3 = cIob.get(1).getDouble("iobStates2");
							iobBasal = cIob.get(1).getDouble("iobBasal");

							log.debug("[ARGPLUGIN:IOMAIN] 	  -> : cIob.size() > 1 - "
									+ "Estados penúltimos. iobLastTime: " + iobLastTime +
									". iobState1: " + iobState1 + ". iobState2: " + iobState2 +
									". iobState3: " + iobState3 + ". iobBasal: " + iobBasal +
									"iobEst: " + iobEst);

							// Seteo como estado inicial el anterior al último que fue incorrectamente
							// actualizado

							double[][] xTemp = {{iobState1}, {iobState2}, {iobState3}};
							Matrix iobState = new Matrix(xTemp);
							gController.getSafe().getIob().setX(iobState);

							// Actualizo el IOB considerando que no se infundió nada

							double[][] uTemp = {{0.0}};
							Matrix u = new Matrix(uTemp);

							for (int jj = 0; jj < (currentTime - iobLastTime) / 60.0 / gController.getSafe().getTs(); ++jj) {

								gController.getSafe().getIob().stateUpdate(u);

							}

							// Recalculo el iobEst
							iobEst = gController.getSafe().getIobEst(gController.getPatient().getWeight());


							// Guardo los estados de IOB corregidos
							double[][] iobStates = gController.getSafe().getIob().getX().getData();


							iobLastTime = currentTime;

							JSONObject statesTableIOB = new JSONObject();
							try {
								statesTableIOB.put("iobStates0", iobStates[0][0]);
								statesTableIOB.put("iobStates1", iobStates[1][0]);
								statesTableIOB.put("iobStates2", iobStates[2][0]);
								statesTableIOB.put("iobEst", iobEst);
								statesTableIOB.put("iobBasal", iobBasal);
								statesTableIOB.put("iobLastTime", iobLastTime);
							} catch (JSONException e) {

							}

							this.insertNewTable("ARG_IOB_STATES", statesTableIOB);

							log.debug("[ARGPLUGIN:IOMAIN] 	  -> : "
									+ "Estados corregidos. iobLastTime: " + iobLastTime +
									". iobState1: " + iobStates[0][0] + ". iobState2: " + iobStates[1][0] +
									". iobState3: " + iobStates[2][0] + ". iobBasal: " + iobBasal +
									"iobEst: " + iobEst);
						} else {
							iobLastTime = currentTime;


							JSONObject statesTableIOB = new JSONObject();
							try {
								statesTableIOB.put("iobStates0", 0);
								statesTableIOB.put("iobStates1", 0);
								statesTableIOB.put("iobStates2", 0);
								statesTableIOB.put("iobEst", 0);
								statesTableIOB.put("iobBasal", iobBasal);
								statesTableIOB.put("iobLastTime", iobLastTime);
							} catch (JSONException e) {

							}

							this.insertNewTable("ARG_IOB_STATES", statesTableIOB);

							log.debug("[ARGPLUGIN:IOMAIN] 	  -> : No había estados penúltimos --> IOB = 0");
						}
					}
				}
				//CASO 4: No hay resultado y sí hay bolo, lo cual no debería pasar
				else
					log.debug("[ARGPLUGIN:IOMAIN] ### Rutina 2 :->  CASO 4: ERROR: No hay resultado y sí hay bolo.");
		}
    }

    private void rutina_3_captura_estados_modelo_iob(){
    	// ************************************************************************************************************ //
		// ************************************************************************************************************ //
    	// Rutina captura de estados del modelo de IOB
		// ************************************************************************************************************ //
		
		log.debug("[ARGPLUGIN:IOMAIN] ### Rutina 3 : Captura Estado - Modelo IOB ###");

    	iobLastTime = 0;
    	iobState1   = 0.0;
    	iobState2   = 0.0;
    	iobState3   = 0.0;
    	
    	// Puntero a la tabla de IOB
    	
		List<ARGTable> cIob = MainApp.getDbHelper()
					.getLastsARGTable("ARG_IOB_STATES", 2);

		
		// TODO_APS: revisar esta logica ya que nuestra base de datos siempre va a devolver los datos
		// asi hayan pasado 80 años

		// Cuando se prende el smartphone luego de mucho tiempo el moveToLast devuelve null por más que haya registros
		// en la tabla. Esto origina que iobLastTime = 0, al igual que los estados. No es un problema, ya que de esta forma 
		// se comienza el proceso de inicialización con los estados en 0, que es lo que adecuado por la diferencia temporal
		// que existe
		
    	if (cIob.size() > 0) {// (cIob != null) {
			log.debug("[ARGPLUGIN:IOMAIN]     -> : cIob.size() > 0");
		
			iobLastTime = cIob.get(0).getLong("iobLastTime");
        	iobState1   = cIob.get(0).getDouble("iobStates0");
        	iobState2   = cIob.get(0).getDouble("iobStates1");
        	iobState3   = cIob.get(0).getDouble("iobStates2");

			if(Objects.equals(iobLastTime, null)){
				iobLastTime = 0;
				iobState1   = 0.0;
				iobState2   = 0.0;
				iobState3   = 0.0;
				
				log.debug("[ARGPLUGIN:IOMAIN]     -> : Captura estados IOB. Iob last time null! --> Iob = 0");  		
			}
    	}else{	
			log.debug("[ARGPLUGIN:IOMAIN]     -> : Captura estados IOB. Error loading iob table! --> Iob = 0");
    		emitToastMsg("Error loading iob table!");
    	} 

    	log.debug("[ARGPLUGIN:IOMAIN]     -> : Captura estados IOB. Estados capturados --> iobState1: "
    		 + iobState1 + ". iobState2: " + iobState2 + ". iobState3: " + iobState3);

    	// Seteo los estados del IOB capturados
    	double[][] xTemp = {{iobState1},{iobState2},{iobState3}};
		Matrix iobState  = new Matrix(xTemp);

		gController.getSafe().getIob().setX(iobState);
    }

    private void rutina_4_inicializacion_iob(){
		
		log.debug("[ARGPLUGIN:IOMAIN] ### Rutina 4 : Inicialización del IOB ###");

		// TODO_APS: obtener valores del perfil de basal, chequear
		// me robo del codigo(DiAS) que está en internet la clase TVector
		// y genero el mismo tipo de datos que utiliza este codigo
		// de esa forma, nos aseguramos el mismo funcionamiento
		// siempre y cuando la adaptacion de los datos sea correcta

		// Primero verifico que el cambio no se produzca a las 00 hs
		double rate00 = profile.getBasalTimeFromMidnight(0 * 60 * 60);
		double rate23 = profile.getBasalTimeFromMidnight(23 * 60 * 60);
		double lastRate = -1;

		//  Esto quiere decir que en 00 hay un cambio, entonces, lo voy a 
		// tomar como cambio inicial, pongo cualquier otra cosa en 
		// lastRate asi en el for lo toma como una diferencia
		if (rate00 != rate23){
			lastRate = -1;

		/// Sino, me interesan los cambios realizados despues de las 12
		// ya que la basal a las 00 arranca antes, por lo tanto la diferencia
		// se tiene encuenta desde ese instante anterior
	    }else{
	    	lastRate = rate00;
	    }

		subjectBasal = new Tvector();
        for (int i = 0; i < 24; i++) {
        	// obtengo basal de la hora i
            double rate = profile.getBasalTimeFromMidnight(i * 60 * 60);

            // TODO_APS: Crasheaba si descomentas esto(por algo del funcionamiento)
            // osea, esto desencadena el crash, pero no crashea por este codigo.
            
            // Ahora solo lee las diferencias
            //if (rate != lastRate){
				subjectBasal.put_with_replace(i*60, rate);
			//	lastRate = rate;
            //}
        }
    	
    	
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
			
			log.debug("[ARGPLUGIN:IOMAIN]     -> : Inicialización IOB. Now [sec]: "+timeNowSecs+". UTC_offset_secs: "+UTC_offset_secs+ ". IOB time [min]: " + timeIobSecs/60 + ". Now [min]: " + timeNowSecs/60 + ". timeDiff [sec]: "+timeDiff);
			
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
			    			    		
			// Get basal values
			List<Integer> indicesAux  = new ArrayList<Integer>();
			indicesAux = subjectBasal.find(">", -1, "<", -1); // Cargo todos los índices del vector de insulina basal
			
			long t0 = 0;
			long tf = timeNowSecs/60; // Paso segundos a minutos que es cómo está informado en el DiAs
			
			//iobLastTime = 0;
			
			// Si la diferencia temporal es mayor a 4 hs, entonces tengo que buscar la insulina basal de las últimas 4 hs
		
			if(timeDiff>14405){ // I added 5 s to take into account minimum differences in time synchronization
				
				log.debug( "[ARGPLUGIN:IOMAIN]     -> :  Inicialización IOB. currentTime-iobLastTime>14405");
				
				// Si la hora actual es mayor que las 04 h, entonces busco los índices 4 hs hacia atrás
				
				if(timeNowSecs/60>=240){
					
					log.debug("[ARGPLUGIN:IOMAIN]     -> :  Inicialización IOB. currentTime-iobLastTime>14405. IF");
					
					indices1 = subjectBasal.find(">", timeNowSecs/60-240, "<=", timeNowSecs/60);
					t0 = timeNowSecs/60-240;
					
				}
				
				// Sino, tengo que considerar que 1440 min (24 h) y 0 min (0 h) es el mismo punto temporal en el vector de insulina basal.
				// Por ende, si por ejemplo el tiempo actual es 03 hs, entonces busco entre las 00 y las 03, y luego entre las 23
				// y las 00 hs, para así completar la búsqueda de las 4 hs anteriores
				
				else{
					
					log.debug("[ARGPLUGIN:IOMAIN]     -> :  Inicialización IOB. currentTime-iobLastTime>14405. ELSE");
					
					indices1 = subjectBasal.find(">", -1, "<=", timeNowSecs/60);
					indices2 = subjectBasal.find(">", 1440+timeNowSecs/60-240, "<=", 1440); 
					t0 = 1440+timeNowSecs/60-240;
					
				}
				
				// Si la diferencia temporal es mayor a 8 hs, entonces tomo los estados iniciales iguales a 0
				
				if(timeDiff>2*14400+5){
					
					log.debug("[ARGPLUGIN:IOMAIN]     -> :  Inicialización IOB. timeDiff>2*14400+5. IF");
					
					double[][] xTemp1 = {{0.0},{0.0},{0.0}};
	    			iobState = new Matrix(xTemp1);
		    		gController.getSafe().getIob().setX(iobState);
		    		
				}
				
				// Sino, ocurre que la diferencia temporal es mayor a 4 hs y menor a 8 hs. Por lo tanto, tomo
				// como estados iniciales los últimos informados y los dejo evolucionar a entrada 0 hasta que 
				// la diferencia temporal entre ellos y el tiempo actual sea de 4 hs.
				
				else{
					
					log.debug("[ARGPLUGIN:IOMAIN]     -> :  Inicialización IOB. timeDiff<2*14400+5. IF");
					
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
				
				log.debug("[ARGPLUGIN:IOMAIN]     -> :  Inicialización IOB. currentTime-iobLastTime<=14405");
				
				t0 = timeIobSecs/60; // El tiempo inicial es el de la última actualización
				
				// Si el tiempo actual es mayor a las 04 hs (recordar que la diferencia temporal es menor a 4 hs), ó el tiempo de 
				// la última actualización del IOB es menor que el tiempo actual, busco desde t0 hasta el tiempo actual
				
				if(timeNowSecs/60>=240 || timeIobSecs/60<timeNowSecs/60){
					
					log.debug("[ARGPLUGIN:IOMAIN]     -> :  Inicialización IOB. currentTime-iobLastTime<=14405. IF");
					
					indices1 = subjectBasal.find(">", timeIobSecs/60, "<=", timeNowSecs/60);
					
					log.debug("[ARGPLUGIN:IOMAIN]     -> :  Inicialización IOB. currentTime-iobLastTime<=14405. IF" + "indices1 null?: " + (indices1 == null));
					
				}
				
				// Sino, tengo que considerar nuevamente que 1440 min y 0 min es el mismo punto. Por eso, busco desde t0 a
				// 1440 min y desde 0 min hasta el tiempo actual
				
				else{
					
					log.debug("[ARGPLUGIN:IOMAIN]     -> :  Inicialización IOB. currentTime-iobLastTime<=14405. ELSE");
					
					indices1 = subjectBasal.find(">", -1, "<=", timeNowSecs/60);
					indices2 = subjectBasal.find(">", timeIobSecs/60, "<=", 1440);
					
				}	
			}
			
			// indices1 e indices2 pueden dar null o ser de tamaño 0 en caso que no se haya encontrado nada (ver método find de la
			// clase Tvector en el paquete Sysman
			
			// Si indices1 da null
			if(indices1 == null){
				
				log.debug("[ARGPLUGIN:IOMAIN]     -> : Inicialización IOB. indices1 null");
				
				// Si además indices2 da null, entonces voy a buscar quedarme con el último índice hasta el tiempo actual
				
				if(indices2 == null){
					
					log.debug("[ARGPLUGIN:IOMAIN]     -> : Inicialización IOB. indices1 null & indices2 null");
					
					// Para quedarme con el último índice hasta el tiempo actual, primero capturo todos los posibles índices anteriores
					
					indices1 = subjectBasal.find(">", -1, "<=", timeNowSecs/60);		// Find the list of indices <= time in minutes since today at 00:00
					
					// Si indices1 resulta null amplio la búsqueda a todos los índices
					
					if (indices1 == null) {
						
						indices1 = subjectBasal.find(">", -1, "<", -1);				// Use final value from the previous day's profile
						
					}
					
					// Si indices1 resulta de tamaño 0 amplio la búsqueda a todos los índices
					
					else if (indices1.size() == 0) {
						
						indices1 = subjectBasal.find(">", -1, "<", -1);				// Use final value from the previous day's profile
						
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
						
						log.debug( "[ARGPLUGIN:IOMAIN]     -> : . Inicialización IOB. indices1 null & indices2.size 0");
						
						// Aplico el mismo procedimiento que cuando indices2 era null
						
						indices1 = subjectBasal.find(">", -1, "<=", timeNowSecs/60);		// Find the list of indices <= time in minutes since today at 00:00
						
	    				if (indices1 == null) {
	    					
	    					indices1 = subjectBasal.find(">", -1, "<", -1);				// Use final value from the previous day's profile
	    					
	    				}
	    				
	    				else if (indices1.size() == 0) {
	    					
	    					indices1 = subjectBasal.find(">", -1, "<", -1);				// Use final value from the previous day's profile
	    					
	    				}
	    				
	    				indices.add(indices1.get(indices1.size()-1));
	    				
	    				flagAdd = false;
	    				
					}
					
					// Si indices2 no es de tamaño 0, entonces indices es indices2
					
					else{
						
						log.debug("[ARGPLUGIN:IOMAIN]     -> :  Inicialización IOB. indices1 null & indices2 something");
						
						indices = indices2;
						
					}
					
				}
				
			}
			
			// Aplico el mismo prodecidimiento que antes si indices1 es de tamaño 0
			
			else if (indices1.size() == 0) {
				
				log.debug("[ARGPLUGIN:IOMAIN]     -> : Inicialización IOB. indices1.size 0");
				
				if(indices2 == null){
					
					log.debug("[ARGPLUGIN:IOMAIN]     -> :  Inicialización IOB. indices1.size 0 & indices2 null");
					
					indices1 = subjectBasal.find(">", -1, "<=", timeNowSecs/60);		// Find the list of indices <= time in minutes since today at 00:00
					
					if (indices1 == null) {
						
						indices1 = subjectBasal.find(">", -1, "<", -1);				// Use final value from the previous day's profile
						
					}
					
					else if (indices1.size() == 0) {
						
						indices1 = subjectBasal.find(">", -1, "<", -1);				// Use final value from the previous day's profile
						
					}
					
					indices.add(indices1.get(indices1.size()-1));
					
					flagAdd = false;
					
				}
				
				else{
					
					if(indices2.size()==0){
						
						log.debug("[ARGPLUGIN:IOMAIN]     -> : Inicialización IOB. indices1.size & indices2.size 0");
						
						indices1 = subjectBasal.find(">", -1, "<=", timeNowSecs/60);		// Find the list of indices <= time in minutes since today at 00:00
						
	    				if (indices1 == null) {
	    					
	    					indices1 = subjectBasal.find(">", -1, "<", -1);				// Use final value from the previous day's profile
	    					
	    				}
	    				
	    				else if (indices1.size() == 0) {
	    					
	    					indices1 = subjectBasal.find(">", -1, "<", -1);				// Use final value from the previous day's profile
	    					
	    				}
	    				
	    				indices.add(indices1.get(indices1.size()-1));
	    				
	    				flagAdd = false;
	    				
					}
					
					else{
						
						log.debug("[ARGPLUGIN:IOMAIN]     -> :  Inicialización IOB. indices1.size 0 & indices2 something");
						
						indices = indices2;
						
					}
					
				}
				
			}
			
			// Si indices1 no es null, ni de tamaño 0
			
			else{
				
				log.debug("[ARGPLUGIN:IOMAIN]     -> :  Inicialización IOB. indices1.size != 0");
				
				// Si indices2 es null, defino indices como indices1
				
				if(indices2 == null){
					
					log.debug("[ARGPLUGIN:IOMAIN]     -> : Inicialización IOB. indices1.size != 0 & indices2 null");
					
					indices = indices1;
					
				}
				
				// Ídem para si indices2 es de tamaño 0
				
				else if(indices2.size() == 0){
					
					log.debug( "[ARGPLUGIN:IOMAIN]     -> : Inicialización IOB. indices1.size != 0 & indices2.size 0");
					
					indices = indices1;
					
				}
				
				// Si indices2 no es ni nulo, ni de tamaño 0, entonces indices es la combinación de indices1 y 2.
				// Por cómo están definidos, primero va indices2 y luego indices1
				
				else{
					
					log.debug("[ARGPLUGIN:IOMAIN]     -> :  Inicialización IOB. indices1.size != 0 & indices2 something");
					
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
			
			value = subjectBasal.get_value(indices.get(0));
			p.put(time, value);
			iobInput.add(ii, p);
			
			// Debug
			
			log.debug("[ARGPLUGIN:IOMAIN]     -> :  Inicialización IOB. iobInput[0]: " + iobInput.get(0).value() + ". indices.get(0): " + indices.get(0));
			
			//
			
			// Esta es una manera elegante de recorrer el vector
			
			while(it.hasNext())
			{
				Integer obj = it.next();
				
				log.debug("[ARGPLUGIN:IOMAIN]     -> :   Inicialización IOB. Indices element: " + obj.intValue());
				
				if(ii!=0){
					
					// Capturo el tiempo del segundo índice
					
					time = subjectBasal.get_time(obj.intValue());
					
					// Si el tiempo que capturé es menor que el inicial, entonces hago el corrimiento de 1440 min
					
					if(time-t0<0){
						
						time = time+1440;
						
					}
					
					// Calculo el tiempo en que se aplicará esa infusión basal
					
					timef = time-t0;
	    			value = subjectBasal.get_value(obj.intValue());
	    			iobInput.add(ii, new Pair()); // Tengo que agregar un nuevo par, no puedo redefinir p
	    			iobInput.get(ii).put(timef, value);
	    			
	    			log.debug("ARG /////// DIAS_STATE_CL&OP&ST&SS. Inicialización IOB. iobInput[ii].value: " + iobInput.get(ii).value() + ". iobInput[ii].time: " + iobInput.get(ii).time());
	    			
				}	    			    			

				++ii;
				
			}
			
			log.debug("[ARGPLUGIN:IOMAIN]     -> :   Inicialización IOB. ii: " + ii);
			
			// Aplico el posible corrimiento de 1440 min al tiempo final
			
			if(tf-t0<0){
				
				tf = tf+1440;
				
			}
			
			timef = tf-t0;
			iobInput.add(ii, new Pair());
			iobInput.get(ii).put(timef, value); // El último elemento es el tiempo final y el valor de infusión basal del último índice (se mantiene ya que no había otro cambio)
			
			log.debug("[ARGPLUGIN:IOMAIN]     -> :   Inicialización IOB. iobInput[end].value: " + iobInput.get(ii).value() + ". iobInput[end].time: " + iobInput.get(ii).time());	    			    			
			
			double uDouble = 0.0;
			int kk = 0;
			
			double totalBasal = 0.0; // Acá acumulo toda la cantidad en U de insulina basal que se aplicará
			
			for(int pp = 0; pp <= iobInput.size()-2; ++pp){
				
				totalBasal += (iobInput.get(pp).value()/60.0)*(iobInput.get(pp+1).time()-iobInput.get(pp).time()); // U/min x min
				
				log.debug("[ARGPLUGIN:IOMAIN]     -> :   Inicialización IOB. iobInput.get(pp).value(): " + iobInput.get(pp).value()+ ". iobInput.get(pp).value()/60.0: " + iobInput.get(pp).value()/60.0 + ". time1: " + iobInput.get(pp+1).time() + ". time2: " + iobInput.get(pp).time() + ". totalBasalSum: " + (iobInput.get(pp).value()/60.0)*(iobInput.get(pp+1).time()-iobInput.get(pp).time()));
				
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
			
			log.debug("[ARGPLUGIN:IOMAIN]     -> :  Inicialización IOB. totalBasal: " + totalBasal + ". iobInitBolus: " + iobInitBolus + ". iobInput size: " + iobInput.size() + ". timef: " + timef);
			
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

			log.debug("[ARGPLUGIN:IOMAIN]     -> :   Inicialización IOB. Total n iterations: " + timef/gController.getSafe().getTs() + ". kk: " + kk + ". uDouble: " + uDouble);

			// ************************************************************************************************************ //
			// Guardo el IOB actualizado

			double iobEst   = gController.getSafe().getIobEst(gController.getPatient().getWeight());
			double iobBasal = gController.getSafe().getIobBasal(gController.getPatient().getBasalU(),gController.getPatient().getWeight());
			double[][] iobStates = gController.getSafe().getIob().getX().getData();


			JSONObject statesTableIOB = new JSONObject();
    		
    		iobLastTime = currentTime;
    		
			try{
				statesTableIOB.put("iobStates0", iobStates[0][0]);
				statesTableIOB.put("iobStates1", iobStates[1][0]);
				statesTableIOB.put("iobStates2", iobStates[2][0]);
				statesTableIOB.put("iobEst", iobEst);
				statesTableIOB.put("iobBasal", iobBasal);	
				statesTableIOB.put("iobLastTime", iobLastTime);
			}catch(JSONException e){

			}

			this.insertNewTable("ARG_IOB_STATES", statesTableIOB);

			log.debug("[ARGPLUGIN:IOMAIN]     -> :   Inicialización IOB (Bolo de inicialización). timeDiff: " + timeDiff + ". IobState1: " + iobStates[0][0] + ". IobState2: " + iobStates[1][0] + ". IobState3: " + iobStates[2][0] +". Final IOB: " + iobEst + ". Basal IOB: "+iobBasal+ ". indicesAux Size: " + indicesAux.size() + ". indices Size: " + indices.size());
			
		
			// ************************************************************************************************************ //
			
		}
		
		// Si no se detecta bolo de inicialización
		
		else{
			
			log.debug("[ARGPLUGIN:IOMAIN]     -> :   Inicialización IOB (Bolo de inicialización). IobState1: " + iobState1 + ". IobState2: " + iobState2 + ". IobState3: " + iobState3);
			
		}
    }

    private void rutina_5_actualizar_vector_cgm(){
		// ************************************************************************************************************ //
		// ************************************************************************************************************ //
		
		// Rutina para actualizar el vector de muestras del CGM
		
		// ************************************************************************************************************ //
				
		log.debug("[ARGPLUGIN:IOMAIN] ### Rutina 5 : Actualizacion del vector de muestra del CGM ###");

		// El flag basalCase se utiliza para a través de su activación indicar la falta de información para computar
		// la acción de control, y por ende, el paso a la infusión a lazo abierto si el sistema está en Closed-Loop Mode
		
		basalCase   = false; 
		rCFBolusIni = 0; // Variable para retardar el posible BAC en la inicialización
		
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
    		
    		//Cursor cCGM = getContentResolver().query(Biometrics.CGM_URI, null, null, null, null);

			List<ARGTable> cCGM = MainApp.getDbHelper()
						.getLastsARGTable("Biometrics.CGM_URI", 1);

    		long offsetR = 300; // Offset para considerar la diferencia temporal en segundos entre que el receptor
    						    // envía una nueva muestra y el APC se ejecuta. Como máximo, dado que ambas cosas ocurren
    							// cada 5 min, la diferencia será de 5 min
    		
        	if (cCGM.size() > 0) {
    			
    			yCGM        = cCGM.get(0).getDouble("cgm");
    			timeCGM     = cCGM.get(0).getLong("time");
    			cgmF        = yCGM;
				state       = cCGM.get(0).getInt("state");
				
				if(Objects.equals(timeCGM, null)){
				
					// Debug
		    		
		    		log.debug("[ARGPLUGIN:IOMAIN]     -> :   Actualización vector CGM. timeCGM = null"
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
		    		
		    		log.debug("[ARGPLUGIN:IOMAIN]     -> : Actualización vector CGM. Error. " + 
		    		"diffCGMTime: "+diffCGMTime+". state:" +state);
		    		
		    		//
		    		
					//Toast.makeText(IOMain.this, "Data error or sensor failed. Check CGM sensor!" , Toast.LENGTH_SHORT).show();
					
					cgmOK = false;
					
				}
				
				// Si el estado del CGM es 5: warmup, entonces el sensor estará durante 2 hs
				// en el proceso de warm-up y enviará 0s. En ese caso, no se procede con la
				// actualización del vector de CGM
				
				if(state==5){
					
					// Debug
		    		
		    		log.debug("[ARGPLUGIN:IOMAIN]     -> : Actualización vector CGM. Warm-up period. " + "diffCGMTime: "+diffCGMTime+". state:" +state);
		    		
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
			    		
			    		log.debug("[ARGPLUGIN:IOMAIN]     -> : Actualización vector CGM. CGM connection failed. " +
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
			    		
			    		log.debug("[ARGPLUGIN:IOMAIN]     -> : Actualización vector CGM. CGM connection stablished. " +
			    		"diffCGMTime: "+diffCGMTime);
			    		
			    		//
			    		
    				}

        		}
        	} else{
	    		
        		cgmOK = false;
        		
        		// Debug
        		
	    		log.error("[ARGPLUGIN:IOMAIN]     -> :  Actualización vector CGM. Error loading CGM table!");
	    		
	    		//
	    		
	    		// Toast.makeText(IOMain.this, "Error loading CGM table!" , Toast.LENGTH_SHORT).show();
	    		
        	}  
        	
	        // Tanto cuando la tabla de CGM está posiblemente vacía o cuando existe un error al cargarla,
        	// el flag cgmOK se setea a false, ya que en esas situaciones no se puede armar el vector
        	// CGM
        	
        	// cCGM.close();
        

        	// ************************************************************************************************************ //
        	// Acá se actualiza el vector de muestras del CGM si es posible (depende del cgmOK)
    		
    		long   timeCGMV      = 0;           // Tiempo del último registro de la tabla del vector de CGM
    		long   diffCGMVTime  = 0;			// Diferencia entre el tiempo actual y el último de la tabla del vector de CGM
    		double flag2c2       = 0.0;         // Flag para indicar el contemplar el caso 2c2 indicado en el manual
    											// Lo defino como double porque se guarda en un columna double de la 
    											// tabla de usuario 4
    		long   timeStampCgmV = currentTime; // Time-stamp que para el caso 2c2
    		
    		// Puntero a la tabla del vector de CGM (tabla de usuario 4)
    		//Cursor cCGMV = getContentResolver().query(Biometrics.USER_TABLE_4_URI, null, null, null, null);
    		List<ARGTable> cCGMV = MainApp.getDbHelper().getLastsARGTable("ARG_CGM_VECTOR", 1);

    		boolean hayResultadosEnDB = (cCGMV.size() > 0);
    		boolean ultimoResultado = true;

    		// Si existe la tabla CGM está OK se procede con la actualización
    		if (cgmOK){
	        	if (hayResultadosEnDB){ //(cCGMV != null) {
	        		if (ultimoResultado){ // (cCGMV.moveToLast()) {
	        			
	        			timeCGMV = cCGMV.get(0).getLong("lastUpdated");
	        			flag2c2  = cCGMV.get(0).getDouble("flag2c2");
	        			
	        			// Cargo todos las mediciones almacenadas en la tabla
	        			// Desde "d0": muestra más antigua, hasta "d5": muestra más reciente
	        			
	        			double cgmAux = 0.0;
	        			
	        			for(int ii = 0; ii < gController.getEstimator().getCgmVector().getM(); ++ii){
	        				cgmAux = cCGMV.get(0).getDouble("cgm"+ii);
	        				gController.getEstimator().insert(cgmAux);
	        			}
	        			
	        			diffCGMVTime = currentTime - timeCGMV;
	        			
	        			double[][] cgmVector = gController.getEstimator().getCgmVector().getData();
	        			
		        		// Debug
		        		
		        		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Actualización vector CGM. diffCGMVTime: "+diffCGMVTime+
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
        						
        						log.debug("[ARGPLUGIN:IOMAIN]     -> :  Actualización vector CGM. "
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
	    			    		
	    			    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Actualización vector CGM. Case 2.a");
	    			    		
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
	    			    		
	    			    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Actualización vector CGM. Case 2.b1");
	    			    		
	    			    		//
        						
        					}
        					
        					// Si la pérdida de sincronismo es mayor a 15 min y hay conexión, tengo que reinicializar
        					// el vector de muestras
        					
        					else{
        						
        						// Hay conexión, pero hay pérdida de sincronismo (no remediable por extrapolación)
        						
        						// Case 2)b2) = Case 1)a)
        						
        						log.debug("[ARGPLUGIN:IOMAIN]     -> :  Actualización vector CGM. "
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
					        	
					        	//Cursor cCGMAux = getContentResolver().query(Biometrics.CGM_URI,null,
					    		//         "time>?" + " AND " + "time<?", new String[]{ Long.toString(timeCGM-1505) , Long.toString(timeCGM-1) }, "time DESC");
					        	List<ARGTable> cCGMAux = CGM_URI_query_between_desc_order(timeCGM-1505, timeCGM-1);
					        	// Flag para indicar el fin del bucle
					        	
					        	boolean flagEndIni = false;
					        	
					        	// Genero inicialmente el vector con todos los elementos iguales a la muestra reciente
					        	
					        	for(int ii = 0; ii < gController.getEstimator().getCgmVector().getM(); ++ii){
					        		cgmVectorAux[ii][0] = cgmF;
    			        		}
					        	
					        	// Busco actualizar las muestras anteriores a la reciente si es que se puede
					        	
					        	if (cCGMAux.size() > 0) { //(cCGMAux != null) {
						    		// while(cCGMAux.moveToNext() && !flagEndIni){
					        		for (int ii = 0; ii < cCGMAux.size(); ii++){
					        			if (flagEndIni) break;
						    			
						    			timeCGMAux2 = cCGMAux.get(ii).getLong("time");
						    			cgmAux1     = cCGMAux.get(ii).getDouble("cgm");
						    			
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
						    			
						    			log.debug("[ARGPLUGIN:IOMAIN]     -> : Actualización vector CGM. Case 2.b2."
						    					+ ". cgm: "+cgmAux1);
						    			
						    		}
						    		
						    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Actualización vector CGM. Prueba. "+
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
	    			    		
	    			    		log.debug("[ARGPLUGIN:IOMAIN]     -> : Actualización vector CGM. "
	    			    				+ "Case 2.b2. TERMINO.");
	    			    		
	    			    		//
	    			    		
	    			    		//cCGMAux.close();
	    			    		
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
	    			    		
	    			    		log.debug("[ARGPLUGIN:IOMAIN]     -> : Actualización vector CGM. Case 2.c1");
	    			    		
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
		    			    		
		    			    		log.debug("[ARGPLUGIN:IOMAIN]     -> : Actualización vector CGM. Case 2.c2. "
		    			    				+ "Flag2c2 activado");
		    			    		
		    			    		//
	        						
        						}
        						
        						
        						// Debug
	    			    		
	    			    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Actualización vector CGM. Case 2.c2");
	    			    		
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
	    			    		
	    			    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Actualización vector CGM. Case 2.c3");
	    			    		
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
	        				
    						log.debug("[ARGPLUGIN:IOMAIN]     -> : Actualización vector CGM. "
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
				        	
				        	//Cursor cCGMAux = getContentResolver().query(Biometrics.CGM_URI,null,
				    		//         "time>?" + " AND " + "time<?", new String[]{ Long.toString(timeCGM-1505) , Long.toString(timeCGM-1) }, "time DESC");
				        	
				        	List<ARGTable> cCGMAux = CGM_URI_query_between_desc_order(timeCGM-1505, timeCGM-1);
		
				        	// Flag para indicar el fin del bucle
				        	
				        	boolean flagEndIni = false;
				        	
				        	// Genero inicialmente el vector con todos los elementos iguales a la muestra reciente
				        	
				        	for(int ii = 0; ii < gController.getEstimator().getCgmVector().getM(); ++ii){
				        		cgmVectorAux[ii][0] = cgmF;
			        		}
				        	
				        	// Busco actualizar las muestras anteriores a la reciente si es que se puede
			        	
				        	if (cCGMAux.size() > 0) { //(cCGMAux != null) {
					    		// while(cCGMAux.moveToNext() && !flagEndIni){
				        		for (int ii = 0; ii < cCGMAux.size(); ii++){
				        			if (flagEndIni) break;

					    			timeCGMAux2 = cCGMAux.get(ii).getLong("time");
					    			cgmAux1     = cCGMAux.get(ii).getDouble("cgm");
					    			
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
					    			
					    			log.debug("[ARGPLUGIN:IOMAIN]     -> : Actualización vector CGM. Case 1.a."
					    					+ ". cgm: "+cgmAux1);
					    			
					    		}
					    		
					    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Actualización vector CGM. Prueba. "+
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
    			    		
    			    		log.debug("[ARGPLUGIN:IOMAIN]     -> : Actualización vector CGM. "
    			    				+ "Case 1.a. TERMINO.");
    			    		
    			    		//
    			    		
	        			}
	        			
	        			// No hay conexión
	        			
	        			else{
	        				
	        				// No hay información para armar el vector CGM, por lo que se pasa
    						// a la infusión a lazo abierto si el sistema está en Closed-Loop Mode
	        				
	        				basalCase = true;
	        				
	        				// Debug
    			    		
    			    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Actualización vector CGM. Case 1.b");
    			    		
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
			    		
			    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Actualización vector CGM. Case 1.a. cCGMV == null");
			    		
			    		//
			    		
			    		//Toast.makeText(IOMain.this, "Error loading User Table 4: CGM Vector" , Toast.LENGTH_SHORT).show();
			    		
        			}
	        		
	        		// No hay conexión
	        		
        			else{
        				
        				// No hay información para armar el vector CGM, por lo que se pasa
						// a la infusión a lazo abierto si el sistema está en Closed-Loop Mode
        				
        				basalCase = true;
        				
        				// Debug
			    		
			    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Actualización vector CGM. Case 1.b");
			    		
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
	    		
	    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Actualización vector CGM. Case 1.b");
	    		
	    		//

    		}
    		
    		//cCGMV.close();
    		
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
	    		
	    		log.debug("[ARGPLUGIN:IOMAIN]     -> : Actualización vector CGM. Save CGM Vector");
	    		
				double[][] cgmVector = gController.getEstimator().getCgmVector().getData();
				JSONObject cgmVectorTable = new JSONObject();
	    		try{	
					cgmVectorTable.put("cgm0", cgmVector[0][0]);
					cgmVectorTable.put("cgm1", cgmVector[1][0]);
					cgmVectorTable.put("cgm2", cgmVector[2][0]);
					cgmVectorTable.put("cgm3", cgmVector[3][0]);
					cgmVectorTable.put("cgm4", cgmVector[4][0]);	
					cgmVectorTable.put("cgm5", cgmVector[5][0]);
					cgmVectorTable.put("flag2c2", flag2c2);
	    			cgmVectorTable.put("lastUpdated", timeStampCgmV);
				}catch(JSONException e){

				}

				this.insertNewTable("ARG_CGM_VECTOR", cgmVectorTable);

	    		// Debug
	    	
	    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Actualización vector CGM. CGM Vector: " +
	    		" [0]: " + cgmVector[0][0] + ". [1]: " + cgmVector[1][0] + ". [2]: " + cgmVector[2][0] + ". [3]: " + cgmVector[3][0] + 
	    		". [4]: " + cgmVector[4][0] + ". [5]: " + cgmVector[5][0] + ". timeStampCgmV: " + timeStampCgmV + ". flag2c2: " + flag2c2);
	    		
	    		//
	    		
	    		if(rCFBolusIni!=0){
		    		// Puntero a la tabla del controlador
		    		List<ARGTable> cKStates = MainApp.getDbHelper().getLastsARGTable("ARG_CONTROLLER_STATES",1);
		    		int rCFBolus = 0;
		    		
		    		if (cKStates.size() > 0) { // (cKStates != null) {			        				
	        			lastTime = cKStates.get(0).getLong("time");
	        			rCFBolus = (int)cKStates.get(0).getDouble("rCFBolus");
	        			
	        			// Debug
			    		
			    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Inicialización rCFBolus."
			    				+ " lastTime: "+lastTime+". rCFBolus: "+rCFBolus);
			    		
			    		//
			    		
	        			if (Objects.equals(lastTime, null)){
	        				
	        				lastTime = currentTime;
	        				
	        				// Debug
    			    		
    			    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Inicialización rCFBolus."
    			    				+ " lastTime null --> currentTime");
    			    		
    			    		//
	        				
	        			}
		    		}
		    		
		    		else{
	    			
	        			lastTime = currentTime;
	        			
	        			// Debug
			    		
			    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Inicialización rCFBolus."
			    				+ " cKStates.size() == 0 --> currentTime");
			    		
			    		//
	        			
		    			// Debug
			    		
			    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Inicialización rCFBolus."
			    				+ " Error loading controller's states!");
			    		
			    		//
			    		
			    		//Toast.makeText(IOMain.this, "Error loading HMS STATE ESTIMATE Table" , Toast.LENGTH_SHORT).show();
		    			
		    		}

		    		if(lastTime == currentTime){
						JSONObject statesTableK = new JSONObject();
			    		try{
			    			statesTableK.put("time", currentTime);
							statesTableK.put("rCFBolus", (double)rCFBolusIni);
						}catch(JSONException e){

						}

						this.insertNewTable("ARG_CONTROLLER_STATES", statesTableK);
						
	        		}
	        		
	        		else{
	        			long diffT = currentTime-lastTime;
		        		int nIter = (int)Math.round(diffT/300.0);
		        		
						JSONObject statesTableK = new JSONObject();
		        		
		        		try{
			        		if (nIter-1>0){
			        			if(rCFBolus>=rCFBolusIni+nIter-1){
									statesTableK.put("rCFBolus", (double)rCFBolus);
			        			}
			        			else{
			        				statesTableK.put("rCFBolus", (double)(rCFBolusIni+nIter-1));
			        			}
			        		}
			        		else{
			        			if(rCFBolus>=rCFBolusIni){
			        				statesTableK.put("rCFBolus", (double)rCFBolus);
			        			}
			        			else{
			        				statesTableK.put("rCFBolus", (double)rCFBolusIni);
			        			}
			        			
			        		}
			        	}catch(JSONException e){

			        	}
			    		
						updateHMSTable(lastTime, statesTableK);

	        		}
	    		}
	        			
    		}
    	
    		
		}
    }

    private void rutina_6_deteccion_comida(){
    
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
    		
    		log.debug("[ARGPLUGIN:IOMAIN]     -> : !basalCase. "
    				+ "Detección de anuncio de comida.");
    		
    		//
    		
        	lastTime         = 0;     // Última actualización de la tabla de anuncio
        	mealClass    = 1;     // Clase de comida
        	mealFlag = false; // Flag de anuncio
        	forCon       = 0;     // Flag para indicar si se forzó el reseteo
        							  // No se declara boolean por cómo se termina guardando en la tabla
        							  // 0: No se forzó, 1: Se forzó el reseteo
        	
        	// Puntero a la tabla de anuncio de comida
        	List<ARGTable> cMeal = MainApp.getDbHelper().getLastsARGTable("ARG_MEAL", 1);

        	if (cMeal.size() > 0) { 
    			lastTime  = cMeal.get(0).getLong("time");
	        	mealClass = (int)cMeal.get(0).getDouble("mealClass");
	        	forCon    = (int)cMeal.get(0).getDouble("forCon");
	        	
	        	// Debug
	    		
	    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  lastTime: "+lastTime+". mealClass: "+mealClass+". forCon: "+forCon);
	    		
	    		//

    			if(Objects.equals(lastTime, null)){
    				
    				lastTime  = 0;
    				mealClass = 1;
    				
    				// Debug
		    		
		    		log.debug("[ARGPLUGIN:IOMAIN]     -> : Meal time null! --> Meal time = 0");
		    		
		    		//
		    		
    			}else{
    				if(mealClass==0)
		        		mealClass = 1;
		        }
        	}
        	
        	else{
        		
        		// Debug
	    		
	    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  No hay datos en la tabla de comida! ");
	    		
	    		//
	    	
        	} 

        	timeDiff = currentTime - lastTime; // Diferencia temporal entre el tiempo actual y el último
        									   // registro de la tabla de anuncio

        	// LEA: Si lastTime = 0, es decir no hay tablas, entonces no hay anuncio de comida
        	
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
        	

        	cMeal = USER_TABLE_3_query_lastTime_and_endAggIni(currentTime - 305, 0);
        	

        	int tEndAggIni = 0;
        	
        	if (cMeal.size() > 0) 
        		// TODO_APS: hay que ver cuando se inserta este campo, si era algo
        		// autonomo del DiAS
	        	tEndAggIni = (int)cMeal.get(0).getDouble("endAggIni");
	        	
	        	// Debug
	    		
	    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Actualización tEndAgg. "+
	    		"tEndAggIni: "+tEndAggIni);
	    		
	    		//
	    		
	    		// Cursor cKStates = getContentResolver().query(Biometrics.HMS_STATE_ESTIMATE_URI, null, null, null, null);
	    		List<ARGTable> cKStates = MainApp.getDbHelper()
							.getLastsARGTable("ARG_CONTROLLER_STATES", 1);


	    		if (cKStates.size() > 0) { // (cKStates != null) {
		        				
        			lastTime = cKStates.get(0).getLong("time");
        			
        			// Debug
		    		
		    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Actualización tEndAgg. "
		    				+ "lastTime: "+lastTime);
		    		
		    		//
		    		
        			if (Objects.equals(lastTime, null)){
        				
        				lastTime = currentTime;
        				
        				// Debug
			    		
			    		log.debug("[ARGPLUGIN:IOMAIN]     -> : Actualización tEndAgg."
			    				+ " lastTime null --> currentTime");
			    		
			    		//
        				
        			}

	    		}
	    		
	    		else{
	    			
        			lastTime = currentTime;
        			

	    			// Debug
		    		
		    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Actualización tEndAgg."
		    				+ " Error loading controller's states!");
		    		
		    		//
		    		
		    //		Toast.makeText(IOMain.this, "Error loading HMS STATE ESTIMATE Table" , Toast.LENGTH_SHORT).show();
	    			
	    		}
        		if(lastTime == currentTime){

					JSONObject statesTableK = new JSONObject();
		    		try{
		    			statesTableK.put("time", currentTime);
						statesTableK.put("endAggIni", (double)tEndAggIni);
					}catch(JSONException e){

					}

					this.insertNewTable("ARG_CONTROLLER_STATES", statesTableK);
        		}else{
					JSONObject statesTableK = new JSONObject();
        			
		    		try{
						statesTableK.put("endAggIni", (double)tEndAggIni);
					}catch(JSONException e){

					}

					updateHMSTable(lastTime, statesTableK);
        		}
        	}
        	
        	else{
        		
        		// Debug
	    		
	    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Actualización tEndAgg. Error loading Meal table! ");
	    		
	    		//
	    		
	    	//	Toast.makeText(IOMain.this, "Error loading Meal table!" , Toast.LENGTH_SHORT).show();
	    	
        	} 

        	//cMeal.close();
    }


    private void rutina_7_cargar_estados_controlador(){

		// ************************************************************************************************************ //
		// ************************************************************************************************************ //
		// Rutina para cargar los estados y variables del controlador
	
		lastTime             = 0; // Acá voy a capturar el tiempo de la última actualización de estados del SLQG
		long diffKStatesTime = 0; // Variable para capturar la diferencia temporal entre el tiempo actual y el último asociado
		                          // a la actualización de estados del controlador
		
		// Puntero a la tabla del controlador
		
		//    		Cursor cKStates = getContentResolver().query(Biometrics.HMS_STATE_ESTIMATE_URI, null, null, null, null); 
		List<ARGTable> cKStates = MainApp.getDbHelper()
							.getLastsARGTable("ARG_CONTROLLER_STATES", 1);

    	if (cKStates.size() > 0) {

			// rCFBolus y tEndAgg son los contadores asociados a los BACs
			
			// Si hay registros guardados los capturo, luego los actualizo al tiempo actual
			
			lastTime     = cKStates.get(0).getLong("time");
			int rCFBolus = (int)cKStates.get(0).getDouble("rCFBolus");
			int tEndAgg  = (int)cKStates.get(0).getDouble("endAggIni");
			
			if(Objects.equals(lastTime, null)){
				
				// Si el puntero devuelve null entonces es probable que no haya ningún registro en la tabla. Podría ocurrir que haya, pero
				// que se haya encendido luego de mucho tiempo, en ese caso la instancia gController tiene los valores por defecto igualmente
				
				// Debug
	    		
	    		log.debug("[ARGPLUGIN:IOMAIN]     -> : Controller's states time null! --> x and variables = 0");
	    		
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
	    		
	    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Updating controller's states. diffKStatesTime: " +
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
		    		
		    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Updating controller's states. diffKStatesTime <455 s");
		    		
		    		//
		    		
		    		// Chequeo si durante la última ejecución en Closed-Loop se pulsó el botón de Reset
		    		
		    		long rTime = 0;
		    		int rFlag  = 0;
		    		lastTime   = 0;
		    		
		        	List<ARGTable> cReset = MainApp.getDbHelper()
						.getLastsARGTable("ARG_MEAL", 1);

		        	if (cReset.size() > 0){ //} (cReset != null) {
	        			rTime    = cReset.get(0).getLong("time"); // Tiempo en que se forzó el modo conservador o se anunció una comida
	        			rFlag    = (int)cReset.get(0).getDouble("forCon"); // Flag para forzar el modo conservador
	        			lastTime = cReset.get(0).getLong("lastTime"); // Tiempo de la última sincronización previa al momento de actualizar la User Table 3
	        			
	        			if(Objects.equals(rTime, null)){
	        				
	        				rTime    = 0;
	        				rFlag    = 0;
	        				lastTime = 0;
	        				
	        				// Debug
    			    		
    			    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  User table 3 time null! --> rTime = 0");
    			    		
    			    		//
    			    		
	        			}
		        	}
		        	
		        	else{
		        		
		        		// Debug
			    		
			    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Error loading User table 3! --> rtime = 0");
			    		
			    		//	
			    		////
			    		// Toast.makeText(IOMain.this, "Checking Force Reset: Error loading User Table 3" , Toast.LENGTH_SHORT).show();
			    		
		        	}
		        	
		        	//cReset.close();
		        	
		        	if(currentTime-rTime<305 && rFlag == 1){
		        		
		        		// Rutina de reseteo del controlador
		        		
		        		// Debug
			    		
			    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Reset command detected. Forcing conservative mode");
			    		
			    		//
			    		
			    		emitToastMsg( "Reset command detected. Forcing conservative mode");
			    		
    					gController.getSlqgController().settMeal(0);
    					gController.getSlqgController().setExtAgg(0);	
    					gController.getEstimator().setListening(0);
    					gController.getEstimator().setMCount(0);
						gController.getSlqgController().setSLQGState(gController.getSlqgController().getConservativeState());
						gController.getSafe().setIOBMaxCF(0.0);
						gController.getSafe().setIobMax(gController.getSafe().getIobMaxSmall());
						gController.setpCBolus(0.0);
						
						JSONObject userTable = new JSONObject();
						try{
    						userTable.put("time", rTime);
    						userTable.put("lastTime", lastTime);
    						userTable.put("mealClass", 1.0);
    						userTable.put("forCon", 0);
    						userTable.put("endAggIni", 0);
			    		}catch(JSONException e){

			    		}

			    		this.insertNewTable("ARG_MEAL", userTable);
		        	}
		        	
		        	else{
		        		
		        		// No se pulsó el botón de reset, por ende se cargan las variables guardadas
		        		
    					int tMeal          = (int)cKStates.get(0).getDouble("tMeal");
    					int extAgg         = (int)cKStates.get(0).getDouble("extAgg");
    					int slqgStateFlag  = (int)cKStates.get(0).getDouble("slqgStateFlag");
    					int listening      = (int)cKStates.get(0).getDouble("Listening");
    					int mCount         = (int)cKStates.get(0).getDouble("MCount");
    					double iobMaxCF    = cKStates.get(0).getDouble("IOBMaxCF");
    					double iobMax      = cKStates.get(0).getDouble("IobMax");
    					double pCBolus     = cKStates.get(0).getDouble("pCBolus");
			        	
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
			    		
			    		log.debug("[ARGPLUGIN:IOMAIN]     -> : Controller's variables loaded --- tMeal: " + tMeal
			    				+ ". extAgg: "+extAgg +". slqgStateFlag: "+slqgStateFlag+ ". mCount: "+mCount + ". listening: "+listening+". IOBMaxCF: "+iobMaxCF
			    				+ ". IobMax: "+iobMax + ". pCBolus: "+pCBolus);
			    		
			    		//
			    		
		        	}
	        					
	        	}
				
				else{
					
					// Si pasan más de 7.5 min en otro modo diferente a Closed-Loop, se ejecuta la rutina de reseteo de las variables
	        		
	        		// Debug
		    		
		    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  More than 7.5 min from last CL update. Reinitilizating the controller variables");
		    		
		    		//
		    		
		    		// Toast.makeText(IOMain.this, "More than 7.5 min from last CL update. Reinitilizating the controller variables" , Toast.LENGTH_SHORT).show();
		    		
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
        			
        			kStatesAux[0][0]  = cKStates.get(0).getDouble("xstates0");
        			kStatesAux[1][0]  = cKStates.get(0).getDouble("xstates1");
        			kStatesAux[2][0]  = cKStates.get(0).getDouble("xstates2");
        			kStatesAux[3][0]  = cKStates.get(0).getDouble("xstates3");
        			kStatesAux[4][0]  = cKStates.get(0).getDouble("xstates4");
        			kStatesAux[5][0]  = cKStates.get(0).getDouble("xstates5");
        			kStatesAux[6][0]  = cKStates.get(0).getDouble("xstates6");
        			kStatesAux[7][0]  = cKStates.get(0).getDouble("xstates7");
        			kStatesAux[8][0]  = cKStates.get(0).getDouble("xstates8");
        			kStatesAux[9][0]  = cKStates.get(0).getDouble("xstates9");
        			kStatesAux[10][0] = cKStates.get(0).getDouble("xstates10");
        			kStatesAux[11][0] = cKStates.get(0).getDouble("xstates11");
        			kStatesAux[12][0] = cKStates.get(0).getDouble("xstates12");
        			
        			kStates = new Matrix(kStatesAux);
        			
				}
				
				else{
					
					// Debug
		    		
		    		log.debug("[ARGPLUGIN:IOMAIN]     -> : More than 12.5 min from last CL update. Reinitilizating the controller states");
		    		
		    		//
		    		
		    		//Toast.makeText(IOMain.this, "More than 12.5 min from last CL update. Reinitilizating the controller states" , Toast.LENGTH_SHORT).show();
					
				}
				
				gController.getSlqgController().getLqg().setX(kStates);
	        				
	        }
    	}
    	
    	else{
    		
    		// Debug
    		
    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Error loading controller's states! --> x and variables = 0");
    		
    		//
    		emitToastMsg("Error loading HMS STATE ESTIMATE Table");
    	
    	}
		
    	//cKStates.close();
    	
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
		
		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Controller State: " + gController.getgControllerState().toString()+ 
				". MealFlag: "+mealFlag +". MealClass: " + mealClass+". yCGM: " +cgmV[gController.getEstimator().getCgmVector().getM()-1][0]+". iobFactor: "+parameterIOBFactor);
		
		//
		
		uControl = gController.run(mealFlag, mealClass, cgmV[gController.getEstimator().getCgmVector().getM()-1][0] ,parameterIOBFactor);

		double sumaDeBACs = gController.getgControllerState().getBACs();
		if (sumaDeBACs > 0){
			JSONObject bacJSONTable = new JSONObject();
			try{
		    		bacJSONTable.put("time", currentTime);
			}catch(JSONException e){

			}
			this.insertNewTable("ARG_REP_BAC", bacJSONTable);
		}


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
    	
		//ContentValues statesTableK = new ContentValues();
		JSONObject statesTableK = new JSONObject();
		double[][] xstates = gController.getSlqgController().getLqg().getX().getData();

		try{
	    		statesTableK.put("time", currentTime);
	    		statesTableK.put("xstates0", xstates[0][0]);
	    		statesTableK.put("xstates1", xstates[1][0]);
	    		statesTableK.put("xstates2", xstates[2][0]);
	    		statesTableK.put("xstates3", xstates[3][0]);
	    		statesTableK.put("xstates4", xstates[4][0]);
	    		statesTableK.put("xstates5", xstates[5][0]);
	    		statesTableK.put("xstates6", xstates[6][0]);
	    		statesTableK.put("xstates7", xstates[7][0]);
	    		statesTableK.put("xstates8", xstates[8][0]);
	    		statesTableK.put("xstates9", xstates[9][0]);
	    		statesTableK.put("xstates10", xstates[10][0]);
	    		statesTableK.put("xstates11", xstates[11][0]);
	    		statesTableK.put("xstates12", xstates[12][0]);
	    		statesTableK.put("tMeal", (double)gController.getSlqgController().gettMeal());
	    		statesTableK.put("extAgg", (double)gController.getSlqgController().getExtAgg());
	    		statesTableK.put("pCBolus", gController.getpCBolus());
	    		statesTableK.put("IobMax", gController.getSafe().getIobMax());
	    		int slqgStateFlag = 0;
	    		if(Objects.equals(gController.getSlqgController().getSLQGState().getStateString(), "Aggressive")){
	    			slqgStateFlag = 1;
	    		}
	    		statesTableK.put("slqgStateFlag", (double)slqgStateFlag);
	    		statesTableK.put("IOBMaxCF", gController.getSafe().getIOBMaxCF());
	    		statesTableK.put("Listening", (double)gController.getEstimator().getListening());
	    		statesTableK.put("MCount", (double)gController.getEstimator().getMCount());
	    		statesTableK.put("rCFBolus", (double)gController.getrCFBolus());
	    		statesTableK.put("endAggIni", (double)gController.gettEndAgg());
		}catch(JSONException e){

		}
		
		this.insertNewTable("ARG_CONTROLLER_STATES", statesTableK);
    }

    private void rutina_8(){
	
		// ************************************************************************************************************ //
    	// ************************************************************************************************************ //

		// Ambos casos el DiAs está en Pump Mode o si el DiAs está en Closed Mode con el flag basalCase activado
		// implican que se infunde la insulina basal
		
		// El caso en que el DiAs está en Pump Mode se agrega aquí para actualizar el IOB correctamente
		
    	if (DIAS_STATE == State.DIAS_STATE_OPEN_LOOP || (DIAS_STATE == State.DIAS_STATE_CLOSED_LOOP && basalCase)){
    		
    		// When the open-loop mode is selected the system does not take into account how much insulin 
    		// the controller requested, so pCBolus should be 0 the first time.
    		
    		// gController.getDiasState()==2 cuando en la iteración anterior estaba en Closed Loop Mode
    		
    		//if(gController.getDiasState()==2 && DIAS_STATE == State.DIAS_STATE_OPEN_LOOP){ 
    		if(DIAS_STATE == State.DIAS_STATE_OPEN_LOOP){ 
    			
    			gController.setpCBolus(0.0);
    			
    			// Debug
	    		
	    		log.debug("[ARGPLUGIN:IOMAIN]     -> : Transición de Closed-Loop a Pump Mode --> pCBolus = 0");
	    		
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
	    		// Cursor bTime = getContentResolver().query(Biometrics.TEMP_BASAL_URI, null, null, null, null);
	    		/*List<ARGTable> bTime = MainApp.getDbHelper().getLastsARGTable("Biometrics.TEMP_BASAL_URI", 1);

	        	if (bTime.size() > 0){ //(bTime != null) {
        			endTime       = bTime.get(0).getLong("scheduled_end_time");
        			startTime     = bTime.get(0).getLong("start_time");
        			actualEndTime = bTime.get(0).getLong("actual_end_time");
        			perTBR        = bTime.get(0).getInt("percent_of_profile_basal_rate");
        			
        			// Debug
		    		
		    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  endTime: "+endTime+". StartTime: "+startTime+
		    				". actualEndTime: "+actualEndTime+". PerTBR: "+perTBR);
		    		
		    		//
		    		
        			if(Objects.equals(startTime, null)){
        				
        				perTBR        = 100;
        				actualEndTime = 0;
        				startTime     = 0;
        				endTime       = 0;
        				
        				// Debug
			    		
			    		log.debug("[ARGPLUGIN:IOMAIN]     -> : TBR start time null! --> Per = 100%");
			    		
			    		//
			    		
        			}	
	        	}
	        	
	        	else{
    				
	        		// Debug
		    		
		    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Error loading TBR table! --> Per = 100%");
		    		
		    		//
		    		
		    		//Toast.makeText(IOMain.this, "Error loading TBR table!" , Toast.LENGTH_SHORT).show();
		    		
	        	}


	        	// ************************************************************************************************************ //
	        	
	        	// Si se forzó el fin del TBR antes de tiempo
	        	
	        	if (actualEndTime!=0){
	        		
	        		// Debug
		    		
		    		log.debug("[ARGPLUGIN:IOMAIN]     -> : actualEndTime!=0");
		    		
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
	        	
	        	
	        	*/

	        	// Verifico de esta forma si hay un TBR activo

	        	final TemporaryBasal activeTemp = TreatmentsPlugin.getPlugin().getTempBasalFromHistory(System.currentTimeMillis());
	    
	            if (activeTemp != null) {
	            	perTBR = activeTemp.percentRate;
    				actualEndTime = 0;
    				startTime     = activeTemp.date / 1000;
    				endTime       = (activeTemp.date / 1000) + (activeTemp.durationInMinutes * 60);

	        		mBasal = activeTemp.percentRate * gController.getPatient().getBasalU()/(12.0);
	            } else {
	            	perTBR = 100;
    				actualEndTime = 0;
    				startTime     = 0;
    				endTime       = 0;

	        		mBasal = gController.getPatient().getBasalU()/(12.0);
	            }

        		// Debug
	    		
	    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  mBasal: "+mBasal + ". Subject basal bolus: "+gController.getPatient().getBasalU()/12.0 + ". perTBR: " + 
	    		perTBR + ". currentTime: " + currentTime + ". startTime: "+ startTime + ". endTime: "+endTime + ". actualEndTime: "+actualEndTime);
	    		
	    		//
	    		
    		}
    		
    		// Si estoy en Closed Loop Mode con el flag basalCase activado
    		// infundo la insulina basal
    		
    		else{
    			
    			mBasal = gController.getPatient().getBasalU()/(12.0);
    			
    			// Debug
	    		
	    		log.debug("[ARGPLUGIN:IOMAIN]     -> : P basalCase. mBasal: "+ mBasal + ". Subject basal bolus: "+gController.getPatient().getBasalU()/12);
	    		
	    		//
	    		
    		}
    		
    		double mBasalF = new BigDecimal(Double.toString(mBasal)).setScale(16, RoundingMode.HALF_DOWN).doubleValue(); // Redondeo el mBasal
    		
    		// Debug
    		
    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  mBasalF: "+ mBasalF + ". Subject basal bolus: "+gController.getPatient().getBasalU()/12);
    		
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
	    		
	    		log.debug("[ARGPLUGIN:IOMAIN]     -> :  basalCase. "+"IOB's states updated. "
	    				+ "basalBolus: " + basalBolus + ". pcb: " + gController.getpCBolus() + ". uTemp: " + uTemp[0][0]);
	    		
	    		//	
	    		
			}
			
			else{
				
				// Debug
	    		
	    		log.debug("[ARGPLUGIN:IOMAIN]     -> : IOB's states updated. basalBolus: " + basalBolus
	    				+ ". pcb: " + gController.getpCBolus() + ". uTemp: " + uTemp[0][0]);
	    		
	    		//
	    		
			}
    		
    	}
    	
    	iobEst   = gController.getSafe().getIobEst(gController.getPatient().getWeight());
    	iobBasal = gController.getSafe().getIobBasal(gController.getPatient().getBasalU(),gController.getPatient().getWeight());
    	
    	// Debug
    	
		log.debug("[ARGPLUGIN:IOMAIN]     -> :  Final IOB: " + iobEst + ". IOB basal: " + iobBasal);
		
		//
		
		// ************************************************************************************************************ //
    	// ************************************************************************************************************ //
		
		// Guardo los estados del IOB si estoy en Pump o Closed Loop Mode
    		    			    		
		if(DIAS_STATE != State.DIAS_STATE_SENSOR_ONLY && DIAS_STATE != State.DIAS_STATE_STOPPED)
		{

			double[][] iobStates1         = gController.getSafe().getIob().getX().getData();

    		iobLastTime = currentTime;

			JSONObject statesTableIOB = new JSONObject();
			try{
				statesTableIOB.put("iobStates0", iobStates1[0][0]);
				statesTableIOB.put("iobStates1", iobStates1[1][0]);
				statesTableIOB.put("iobStates2", iobStates1[2][0]);
				statesTableIOB.put("iobEst", iobEst);
				statesTableIOB.put("iobBasal", iobBasal);	
				statesTableIOB.put("iobLastTime", iobLastTime);
			}catch(JSONException e){

			}

			this.insertNewTable("ARG_IOB_STATES", statesTableIOB);

    		log.debug("[ARGPLUGIN:IOMAIN]     -> : IOB states saved!");
    		
    		//
			
		}
		
		if(DIAS_STATE == State.DIAS_STATE_SENSOR_ONLY || DIAS_STATE == State.DIAS_STATE_STOPPED){
			gController.setpCBolus(0.0);
		}
		
		// ************************************************************************************************************ //
    	// ************************************************************************************************************ //
    }

    public void guardarTablaTotal(){	
    	JSONObject argTableJSON = new JSONObject();
    	double yCGM = -1;
		double[][] iobStates = gController.getSafe().getIob().getX().getData();
		double[][] xstates = gController.getSlqgController().getLqg().getX().getData();
		boolean anuncio;
		int slqgStateFlag = 0;
		int tamanio;

		List<ARGTable> cCGM = MainApp.getDbHelper()
					.getLastsARGTable("Biometrics.CGM_URI", 1);

		if (cCGM.size() > 0) 
			yCGM        = cCGM.get(0).getDouble("cgm");

		tamanio = mealClass;
		anuncio = mealFlag;

		if (Objects.equals(gController.getSlqgController().getSLQGState().getStateString(), "Aggressive"))
			slqgStateFlag = 1;

		try{

			argTableJSON.put("time",String.valueOf(DateUtil.now()));
	        argTableJSON.put("CGM", yCGM);
	        argTableJSON.put("xstates[0][0]", String.valueOf(xstates[0][0]));
	        argTableJSON.put("xstates[1][0]", String.valueOf(xstates[1][0]));
	        argTableJSON.put("xstates[2][0]", String.valueOf(xstates[2][0]));
	        argTableJSON.put("xstates[3][0]", String.valueOf(xstates[3][0]));
	        argTableJSON.put("xstates[4][0]", String.valueOf(xstates[4][0]));
	        argTableJSON.put("xstates[5][0]", String.valueOf(xstates[5][0]));
	        argTableJSON.put("xstates[6][0]", String.valueOf(xstates[6][0]));
	        argTableJSON.put("xstates[7][0]", String.valueOf(xstates[7][0]));
	        argTableJSON.put("xstates[8][0]", String.valueOf(xstates[8][0]));
	        argTableJSON.put("xstates[9][0]", String.valueOf(xstates[9][0]));
	        argTableJSON.put("xstates[10][0]", String.valueOf(xstates[10][0]));
	        argTableJSON.put("xstates[11][0]", String.valueOf(xstates[11][0]));
	        argTableJSON.put("xstates[12][0]", String.valueOf(xstates[12][0]));

	        argTableJSON.put("tMeal",String.valueOf((double) gController.getSlqgController().gettMeal()));
	        argTableJSON.put("ExtAgg",String.valueOf((double) gController.getSlqgController().getExtAgg()));
	        argTableJSON.put("pCBolus" ,String.valueOf(gController.getpCBolus()));
	        argTableJSON.put("IobMax",String.valueOf(gController.getSafe().getIobMax()));
	        argTableJSON.put("slqgState",String.valueOf(slqgStateFlag));
	        argTableJSON.put("IOBMaxCF",String.valueOf(gController.getSafe().getIOBMaxCF()));
	        argTableJSON.put("Listening",String.valueOf((double) gController.getEstimator().getListening()));
	        argTableJSON.put("MCount", String.valueOf((double) gController.getEstimator().getMCount()));
	        argTableJSON.put("rCFBolus", String.valueOf((double) gController.getrCFBolus()));
	        argTableJSON.put("tEndAgg", String.valueOf((double) gController.gettEndAgg()));
	        argTableJSON.put("iobStates[0][0]", String.valueOf(iobStates[0][0]));
	        argTableJSON.put("iobStates[1][0]",String.valueOf(iobStates[1][0]));
	        argTableJSON.put("derivadaIOB", String.valueOf(iobStates[2][0]));
	        argTableJSON.put("iobEst", String.valueOf(gController.getSafe().getIobEst(gController.getPatient().getWeight())));
	        argTableJSON.put("Gamma", String.valueOf(gController.getSafe().getGamma()));

	        argTableJSON.put("Anuncio", String.valueOf(anuncio));
	        argTableJSON.put("Tamanio", String.valueOf(tamanio));

	        argTableJSON.put("Resultado", String.valueOf(correction + diff_rate));
		}catch(JSONException e){

		}

		this.insertNewTable("ARG_TOTAL_STATES", argTableJSON);
    }

    private boolean rutina_chequeo_bolo_inicializacion(){

        long tolTimeMin = 30; // 30 minutos, cambiar este valor
        boolean forceInitIob = false;
		List<ARGTable> cIob = MainApp.getDbHelper().getLastsARGTable("ARG_IOB_STATES", 1);

		if (cIob.size() > 0){
			long time = cIob.get(0).getLong("iobLastTime");
			if (time < (nowMS / 1000) - (tolTimeMin * 60)){
				forceInitIob = true;

				log.debug("[ARGPLUGIN] Forzando inicializar insulina para IOB: tolerancia superada");
			}else{
				log.debug("[ARGPLUGIN] Hay iob recientes");
			}
		}else{
			forceInitIob = true;
			log.debug("[ARGPLUGIN] Forzando inicializar insulina para IOB: no hay IOB");
		}	

		// Controlo que no se haya dado el bolo de inicalizacion
		if (forceInitIob){
			List<ARGTable> insulin = INSULIN_URI_query_reqtime_and_type((nowMS / 1000 - 300), 3);
			if (insulin.size() == 0){
				// Llamar a ventana de dialogo

				ARGFragment.showInitBolus();

		        //FragmentManager manager = MainApp.instance().getApplicationContext().getFragmentManager();
		        //new NewInitBolusDialog().show(manager, "InitBolusDialog");
		       // FabricPrivacy.getInstance().logCustom(new CustomEvent("ARG_Init_Bolus"));

				log.debug("[ARGPLUGIN] Forzado inicializar insulina para IOB: llamado a ventana de dialogo");
				return false;
			}else{
				log.debug("[ARGPLUGIN] Forzado inicializar insulina para IOB: ya fue ingresada");
			}
		}
		
		return true;

    }

	public double ejecutarCada5Min(GController g) {
        profile = ProfileFunctions.getInstance().getProfile();
        gController = g;
		nowMS = System.currentTimeMillis();

		// Debug
		log.debug("[ARGPLUGIN:IOMAIN] ejecutarCada5Min()");

        if (gController == null){
			log.debug("[ARGPLUGIN:IOMAIN] ejecutarCada5Min() NO HAY CONTROLADOR ACTIVO");
			return -1;
        }else if (profile == null){
			log.debug("[ARGPLUGIN:IOMAIN] ejecutarCada5Min() NO HAY PERFIL ACTIVO");
			return -1;
        }


        if (!rutina_chequeo_bolo_inicializacion())
        	return -1;

        // Si ejecutar cada 5 min no tiene un valor es porque se destruyó el controlador
        // puede ser que se haya intercambiado de plugins en menos de 5 min
        // que la app haya crasheado, o forzado detencion, etc.
		List<ARGTable> cResult = MainApp.getDbHelper().getLastsARGTable("ARG_RESULT", 1);
		
        if (lastEjectuarCada5Min_tick == -1){
			if (cResult.size() > 0){
				if (nowMS - cResult.get(0).date < ( ( (5 * 60) - 10)  * 1000L )){
					lastEjectuarCada5Min_tick = cResult.get(0).date;
					return -1;
				}
			}
        }

		// Clonacion de tablas de AAPS a como las adquiere DiAS
		this.AAPStoDiAS();

		// TODO_AAPS: como determinar esto?
		asynchronous = false;

		// Lee el estado del AAPS, si está en lazo abierto o cerrado y lo asigna al DIAS_STATE
		Constraint<Boolean> closedLoopEnabled = MainApp.getConstraintChecker().isClosedLoopAllowed();
		if(closedLoopEnabled.value()==true)
			DIAS_STATE = State.DIAS_STATE_CLOSED_LOOP;
		else
			if(closedLoopEnabled.value()==false)
				DIAS_STATE = State.DIAS_STATE_OPEN_LOOP;
		
		// Iniciar variables
		correction = 0.0;
		diff_rate = 0.0;
		new_rate = false;
		

		if (nowMS - lastEjectuarCada5Min_tick < ( ( (5 * 60) - 10)  * 1000L ) ){
			log.debug("[ARGPLUGIN:IOMAIN] ejecutarCada5Min() < 5 min(-10segs), exit");
			return -1;
		}

		lastEjectuarCada5Min_tick = nowMS;

		// ************************************************************************************************************ //
		
		// Esto se ejecuta con el DiAs en cualquier modo
		
		if (DIAS_STATE == State.DIAS_STATE_CLOSED_LOOP || DIAS_STATE == State.DIAS_STATE_OPEN_LOOP || DIAS_STATE == State.DIAS_STATE_STOPPED || DIAS_STATE == State.DIAS_STATE_SENSOR_ONLY) {
			
			// Only run this in synchronous mode (asynchronous is for meals)
			
			// Debug
			
			log.debug("[ARGPLUGIN:IOMAIN]  Nueva iteracion");
			
			//
			
			if (!asynchronous) {
				// Debug
	    		
	    		log.debug("[ARGPLUGIN:IOMAIN] Llamado sincronico");
	    		
	    		//
		
				//Outputting in this loop ensures that you have all the insulin profile data you need
				
				//The values CF, CR, and Basal are the current values from the time you call "read()" (as shown above)
				/// *********************************************************
				//Probably best to insert your calculation routine here...
				
				// TODO_AAPS: subject es como la variable de la app del DiAS global que almacenaba informacion
				// creo que aca no vamos a llamar a estas rutinas 

	    		double parameterCF = profile.getIsf();
	    		double parameterCR = profile.getIc();
	    		double parameterBasal = profile.getBasal();
	    		double parameterTDI = SP.getDouble(R.string.key_apsarg_tdi, 4d);
	    		double parameterWeight = SP.getDouble(R.string.key_apsarg_weight, 4d);
	    		double parameterSetpoint = SP.getDouble(R.string.key_apsarg_setpoint, 4d);
				parameterIOBFactor = SP.getDouble(R.string.key_apsarg_iobfactor, 4d);

				gController.getPatient().setTdi(parameterTDI); 
				gController.getPatient().setWeight(parameterWeight); 
				gController.setSetpoint(parameterSetpoint);
				gController.getPatient().setCf(parameterCF);
				gController.getPatient().setCr(parameterCR);
				gController.getPatient().setBasalU(parameterBasal);

				// Actualizar interfaz

				// Debug
	    		

	    		log.debug("[ARGPLUGIN:IOMAIN] CR: " + parameterCR + " - CF: " + parameterCF +
	    			" - basalU: " + parameterBasal + " - TDI: " + parameterTDI + " - Weight: " + parameterWeight + 
	    			" - setPoint: " + parameterSetpoint + " - IOB_factor: " + parameterIOBFactor);


	    		rutina_1_capturar_bolos_asincronicos();

	    		rutina_2_correccion_iob_bolos_sincronicos_no_infundidos();
	    		
	    		rutina_3_captura_estados_modelo_iob();
	        	
	        	rutina_4_inicializacion_iob();

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
					double[][] iobStates = gController.getSafe().getIob().getX().getData();

					JSONObject statesTableIOB = new JSONObject();
    				iobLastTime = currentTime;

					try{
						statesTableIOB.put("iobStates0", iobStates[0][0]);
						statesTableIOB.put("iobStates1", iobStates[1][0]);
						statesTableIOB.put("iobStates2", iobStates[2][0]);
						statesTableIOB.put("iobEst", iobEst);
						statesTableIOB.put("iobBasal", iobBasal);	
						statesTableIOB.put("iobLastTime", iobLastTime);
					}catch(JSONException e){

					}

					this.insertNewTable("ARG_IOB_STATES", statesTableIOB);
	        	}
	        	
	    		double iobEst   = gController.getSafe().getIobEst(gController.getPatient().getWeight());
	    		double iobBasal = gController.getSafe().getIobBasal(gController.getPatient().getBasalU(),gController.getPatient().getWeight());
	    		double[][] iobStates = gController.getSafe().getIob().getX().getData();
	    		
	    		// Debug
	    		
	    		log.debug("[ARGPLUGIN:IOMAIN]  Inicialización IOB (Final). IobState1: " + iobStates[0][0] + ". IobState2: " + iobStates[1][0] + ". IobState3: " + iobStates[2][0] +". Final IOB: " + iobEst + ". Basal IOB: "+iobBasal);
				
	    		//
	    		
	    		rutina_5_actualizar_vector_cgm();

        		rutina_luces_semaforo();
	    		
	    		rutina_6_deteccion_comida();

	    		rutina_7_cargar_estados_controlador();
        		
        		rutina_8();

        		guardarTablaTotal();

        		// TODO_APS Guardar la informacion si hubo algun bac
        		// en ARG_REP_BAC -> unico campo 'time' para dibujar el triangulito 
			}else{
				
				// Debug
				
				log.debug("[ARGPLUGIN:IOMAIN]  Asynchronous call!");
	    		
	    		//
				
			}
	    	
	    	// TODO_AAPS: este metodo no esta en esta versión
			// gController.setDiasState(DIAS_STATE);
		
		// All other modes...
		// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
		
			
		}else{
			
			// Debug
			
			log.debug("[ARGPLUGIN:IOMAIN] DIAS_STATE_UNKWOWN");
			
			//
			
		}

		// Devuelvo el bolo en total
		return correction + diff_rate;
	}

	private List<ARGTable> INSULIN_URI_query_deliv_time(long greaterTime){
		// deliv_time: cuando lo termino dar
		// supongo que como maximo lo solicitó dos horas antes
		// obtengo todo esos bolos y los filtro por el verdadero campo

		List<ARGTable> ret = MainApp.getDbHelper()
			.getAllARGTableFromTimeByDiASType("Biometrics.INSULIN_URI", 
					greaterTime - (2*3600*1000L), false);

		ret.removeIf(item -> item.getLong("deliv_time") < greaterTime);

		return ret;
	}

	private List<ARGTable> INSULIN_URI_query_reqtime_and_type(long greaterTime, int type){
		// req: cuando lo solicito, la tabla seguramente se cree despues
		// pero  agrego inconsistencia de 10 minutos,
		// obtengo todo esos bolos y los filtro por el verdadero campo

		List<ARGTable> ret = MainApp.getDbHelper()
				.getAllARGTableFromTimeByDiASType("Biometrics.INSULIN_URI",
						greaterTime - (10*60*1000L), false);

		ret.removeIf(item -> (
				(item.getLong("time") < greaterTime) ||
						(item.getInt("type") != type)
		));


		return ret;
	}

	private ARGTable ARG_RESULT_query(long greaterTime){
		List<ARGTable> ret = MainApp.getDbHelper()
				.getAllARGTableFromTimeByDiASType("ARG_RESULT",
						greaterTime - (10*60*1000L), false);
		if((ret.size()>0) && (ret.get(0).date > greaterTime*1000))
			return ret.get(0);
		else
			return null;
	}

	private List<ARGTable> CGM_URI_query_between_desc_order(long greaterTime, long lowerTime){
		List<ARGTable> ret = MainApp.getDbHelper()
			.getAllARGTableFromTimeByDiASType("Biometrics.CGM_URI", greaterTime, false);

		ret.removeIf(item -> (item.date > lowerTime));
		return ret;
	}

	
	private List<ARGTable> USER_TABLE_3_query_lastTime_and_endAggIni(long greaterLastTime, int endAggIni){
		List<ARGTable> ret = MainApp.getDbHelper()
			.getAllARGTableFromTimeByDiASType("ARG_MEAL", 
					greaterLastTime - (10*60*1000L), false);

		ret.removeIf(item -> (
			(item.getLong("lastTime") <= greaterLastTime) && 
			(item.getInt("endAggIni") == endAggIni)
		));

		return ret;
	}

	private void updateHMSTable(long lastTime, JSONObject newTableValues){
		List<ARGTable> ret = MainApp.getDbHelper()
			.getAllARGTableFromTimeByDiASType("ARG_CONTROLLER_STATES", 
					lastTime - (12*60*1000L), false);
		
		ret.removeIf(item -> (
			(item.getLong("time") != lastTime)
		));

		
		// paso 1 Obtener json viejo
			// obtener los ultimos 10 registros
			// ver cual tiene ese lastTime

		if (ret.size() > 0){
			ARGTable obj = ret.get(0);
			JSONObject oldData = obj.getAllData();

			// paso 2 reemplazar que aparezcan en newTablesValues
			for ( Iterator<String> iterator = newTableValues.keys(); iterator.hasNext(); ) {
		        String      key     = iterator.next();
		        JSONObject  value   = newTableValues.optJSONObject(key);

		        try {
		            oldData.put(key, value);
		        } catch ( JSONException e ) {
		        	log.debug("[ARGPLUGIN] Error al copiar clave en estados de controlador.");
		        }
		    }

		   	obj.setData(oldData);

			// paso 3 llamar a actualziar db
		   	MainApp.getDbHelper().updateARGTable(obj);

			// paso 4 llamar a actualizar nightscout 
		   	// TODO_APS: faltaría subir la actualizacion a nightscout :/
		}else{
			log.debug("[ARGPLUGIN] NO SE PUEDE ACTUALIZAR CONTROLLER STATE, NO EXISTE EL REGISTRO.");
		}

		log.debug("[ARGPLUGIN] Llamado a actualizar variables del controlador");
	}

	public void insertNewTable(String table, JSONObject argTableJSON){
		ARGTable argTable = new ARGTable(System.currentTimeMillis(), table, argTableJSON);

		MainApp.getDbHelper().createARGTableIfNotExists(argTable, "insertNewTable()");
		NSUpload.uploadARGTable(argTable);
	}


	private void cargarCSV(){

    	/*
		String fileName = "TablaDeDatos.csv";
		String filePath = baseDir + File.separator + fileName;
		CSVWriter writer = null;
		// File exist
		try {
			FileWriter mFileWriter = new FileWriter(filePath, true);
			writer = new CSVWriter(mFileWriter);
			if(firstExecution) {
				String[] headerRecord = {"time", "CGM", "xstates[0][0]", "xstates[1][0]", "xstates[2][0]", "xstates[3][0]", "xstates[4][0]", "xstates[5][0]", "xstates[6][0]", "xstates[7][0]", "xstates[8][0]", "xstates[9][0]", "xstates[10][0]", "xstates[11][0]", "xstates[12][0]",  "tMeal", "ExtAgg", "pCBolus", "IobMax", "slqgState", "IOBMaxCF", "Listening", "MCount", "rCFBolus", "tEndAgg", "iobStates[0][0]", "iobStates[1][0]", "derivadaIOB", "iobEst", "Gamma", "Anuncio", "Tamaño", "Resultado"};
				writer.writeNext(headerRecord);
				firstExecution=false;
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		int slqgStateFlag = 0;
		if (Objects.equals(gController.getSlqgController().getSLQGState().getStateString(), "Aggressive"))
			slqgStateFlag = 1;
		iobPrueba=gController.getSafe().getIobEst(gController.getPatient().getWeight());
		String [] dataToCSV = {String.valueOf(DateUtil.now()), String.valueOf(data.get(0).raw), String.valueOf(xstates[0][0]), String.valueOf(xstates[1][0]), String.valueOf(xstates[2][0]), String.valueOf(xstates[3][0]), String.valueOf(xstates[4][0]), String.valueOf(xstates[5][0]), String.valueOf(xstates[6][0]), String.valueOf(xstates[7][0]), String.valueOf(xstates[8][0]), String.valueOf(xstates[9][0]), String.valueOf(xstates[10][0]), String.valueOf(xstates[11][0]), String.valueOf(xstates[12][0]),  String.valueOf((double) gController.getSlqgController().gettMeal()), String.valueOf((double) gController.getSlqgController().getExtAgg()), String.valueOf(gController.getpCBolus()), String.valueOf(gController.getSafe().getIobMax()), String.valueOf(slqgStateFlag), String.valueOf(gController.getSafe().getIOBMaxCF()), String.valueOf((double) gController.getEstimator().getListening()), String.valueOf((double) gController.getEstimator().getMCount()), String.valueOf((double) gController.getrCFBolus()), String.valueOf((double) gController.gettEndAgg()), String.valueOf(iobStates[0][0]), String.valueOf(iobStates[1][0]), String.valueOf(iobStates[2][0]), String.valueOf(gController.getSafe().getIobEst(gController.getPatient().getWeight())), String.valueOf(gController.getSafe().getGamma()),  String.valueOf(comida),String.valueOf(tamanio),  String.valueOf(resultado)};

		writer.writeNext(dataToCSV);
		try {
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		*/
	}

    private void rutina_luces_semaforo(){

		/*
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
		*/

    }
}