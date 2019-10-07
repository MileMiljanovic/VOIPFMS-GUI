package com.devoteam.voipfms.gui.web;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import redis.clients.jedis.Jedis;

@RestController
public class WebController {

	private Jedis jedis;
	
	@Value("${spring.redis.host}")
	private String redisHostName;

	@Value("${spring.redis.port}")
	private int redisPort;
	
	@Value("${spring.redis.retry.interval}")
	private int retryInterval;
	
	@Value("${logging.file}")
    private String loggingFile;
	
	@Value("${spring.log.level}")
    private String loggingLevel;
	
	private boolean redisConnected;
	
	private static Logger LOGGER = Logger.getLogger(WebController.class.getName());
	
	
	@PostConstruct
	void init() throws SecurityException, IOException {
		
		setLoggerHandler();  //set new handlers
		redisConnected = false;
		LOGGER.log(Level.INFO, "VOIP FMS GUI initialized!");
		connectThread();
	}
	
	public void setLoggerHandler() throws SecurityException, IOException {
		
		LOGGER.setUseParentHandlers(false); //disable default handlers
		Level level = Level.parse(loggingLevel);  //get logging level specified in properties
		FileHandler handler = new FileHandler(loggingFile, true);
		handler.setFormatter(new SimpleFormatter() {
            private static final String format = "[%1$tF %1$tT.%1$tL] [%2$-7s] %3$s %n";

            @Override
            public synchronized String format(LogRecord lr) {
                return String.format(format,
                        new Date(lr.getMillis()),
                        lr.getLevel().getLocalizedName(),
                        lr.getMessage()
                );
            }
        });
		LOGGER.addHandler(handler);
		ConsoleHandler chandler = new ConsoleHandler();
		LOGGER.addHandler(chandler);
		handler.setLevel(level);
		chandler.setLevel(level);
		LOGGER.setLevel(level);
	}
	
	public void connectThread() {
		new Thread() {
		    @Override
		    public void run() {
		        try {
		        	redisConnection();	// start a separate thread which will retry to connect to redis
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					LOGGER.log(Level.SEVERE, "Exception has occured! Cause: " + e.getCause() + ", Details: " + e.getMessage());
				}
		    }
		}.start();
	}
	
	public void redisConnection() throws InterruptedException {
		
		while (!redisConnected) {
			
			try {
				jedis = new Jedis(redisHostName, redisPort);    //try to connect to redis
				LOGGER.log(Level.INFO, "Pinging Redis...");
				jedis.ping();
				redisConnected = true;
				LOGGER.log(Level.INFO, "Successfully connected to Redis");
				return;
			}
			catch (Exception e) {	//if connecting to redis was not successful
				jedis.close();   //close session
				redisConnected = false;
				LOGGER.log(Level.SEVERE, "Connecting to redis failed! Cause: " + e.getCause() + ", Details: " + e.getMessage());
				Thread.sleep(retryInterval);
			}
		}
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@GetMapping(value = "/getSubscriber/{id}")
	public ResponseEntity<HashMap<String, String>> findItem(@PathVariable("id") final String id) throws InterruptedException {
		
		LOGGER.log(Level.INFO, "REST request arrived! Requested id: " + id);
		
		if (!redisConnected) {
			LOGGER.log(Level.SEVERE, "Subscriber with MSISDN: " + id + " not found because Redis is unavailable! Try again later.");
			return new ResponseEntity(null, HttpStatus.SERVICE_UNAVAILABLE);
		}
		
		try {
			String res = jedis.get(id);
			System.out.println(res);
			if (res == null || res.trim().equals("") || res.trim().equals("null")) {
				LOGGER.log(Level.INFO, "Subscriber with MSISDN: " + id + " does not exist! Returning 404...");
				return new ResponseEntity(null, HttpStatus.NOT_FOUND);
			}
			LOGGER.log(Level.INFO, "Subscriber with MSISDN: " + id + " found! Returning response...");
			return new ResponseEntity(res, HttpStatus.OK);
		}
		catch (Exception e) {
			LOGGER.log(Level.SEVERE, "Exception has occured while fetching data! Cause: " + e.getCause() + ", Details: " + e.getMessage());
			redisConnected = false;
			connectThread();
			return new ResponseEntity(null, HttpStatus.SERVICE_UNAVAILABLE);
		}

	}
	
	@PreDestroy
	public void delete() throws IOException {    //before we destroy the bean, close the connection towards redis
		jedis.close();
	}
	

}
