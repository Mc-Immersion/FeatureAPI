package cf.mcdteam.featureAPI.loader;

import net.minecraftforge.common.config.Configuration;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cf.mcdteam.featureAPI.FeatureAPI;
import cf.mcdteam.featureAPI.configuration.FeatureConfigurationProvider;
import cf.mcdteam.featureAPI.loader.Feature.FeatureData;
import cf.mcdteam.featureAPI.loader.Feature.FeatureElement;
import cf.mcdteam.featureAPI.loader.Feature.FeatureData.Data;
import cf.mcdteam.featureAPI.loader.Feature.FeatureElement.Element;
import cf.mcdteam.featureAPI.object.FeatureObjectRegister;

/**
 *  Feature Repository is used for registering, storing and retrieving of features, with automated initialization delegation
 */
public class FeatureLoader {
	public FeatureLoader INSTANCE = new FeatureLoader();
    private final Logger _logger;
    private HashMap<String, IFeature> _features;
    private HashMap<IFeature, FeatureObjectRegister> _featureObjectRegisters;

    private FeatureLoader(){
        this._features = new HashMap<String, IFeature>();
        this._logger = LogManager.getLogger("FeatureAPI] [Feature Loader");
        this._featureObjectRegisters = new HashMap<IFeature, FeatureObjectRegister>();
    }

    public void RegisterFeature(IFeature feature) 
    {
        this._features.put(FeatureDataCollector.instance.getFeatureName(feature), feature);
        
    }

    public void runSetup(Configuration configuration)
    {
        ArrayList<IFeature> _fullFeatures = new ArrayList<IFeature>();
    	ArrayList<IFeature> _alternateFeatures = new ArrayList<IFeature>();
    	ArrayList<IFeature> _methodFeatureStorage = new ArrayList<IFeature>();
    	
    	_logger.info("Now setting up the Feature Repository");
    	
    	//Processing of Pre-Data Annotations
    	this.fillData(Data.PREFEATURELIST, this._features, this._features);
    	this.fillData(Data.MODINSTANCE, FeatureAPI.instance, this._features);
    	for (IFeature feature: this._features.values())
    	{
    		for (Field feild : feature.getClass().getFields())
    		{
    			try
    			{
    				FeatureData data = feild.getAnnotation(Feature.FeatureData.class);
    				if (data.value() == Data.LOGGER)
    				{
    					feild.set(feature, LoggerProvider.getLoggerForFeature(feature));
    					continue;
    				}
    			}
    			catch (Throwable e)
    			{
    				this._logger.info("Scanning for feilds resulted in '%1$s' from feature '%2$s' for feild '%3$s'.", e.toString(), feature.getClass().getAnnotation(Feature.class).name(), feild.getName());
    			}	
    		}
    	}
    	
    	//Run Startup Code
    	for (IFeature feature: this._features.values())
    	{
    		feature.preSetup();
    	}
    	
    	//Getting all dependencies
    	HashMap<IFeature, IFeature[]> map = new HashMap<IFeature, IFeature[]>();
    	for (IFeature feature: this._features.values())
    	{
    		IFeature[] features = feature.setup();
    		if (features != null)
    		{
    			map.put(feature, features);
    		}
    	}
    	
    	if (!map.isEmpty()) 
    	{
			//Find toplevels and ask about them. Goes to the smallest toplevel and then stops
			int topFound;
			do {
				topFound = 0;
				Iterator<IFeature> iterator = this._features.values().iterator();
				do {
					IFeature feature = iterator.next();
					if (feature.getClass().getAnnotation(Feature.class)
							.isBase()) {
						_fullFeatures.add(feature);
						iterator.remove();
						continue;
					} else {
						Boolean depFound = false;
						for (IFeature[] deplist : map.values()) {
							for (IFeature dependency : deplist) {
								if (dependency == feature) {
									depFound = true;
									break;
								}
							}
							if (depFound == true) {
								break;
							}
						}
						if (depFound == true) {
							continue;
						} else {
							topFound++;
							Boolean active = configuration.get(
									"Feature Activation",
									String.format("Feature '%1$s' active",
											FeatureDataCollector.instance.getFeatureName(feature)), true)
									.getBoolean(true);
							if (active) {
								_fullFeatures.add(feature);
								iterator.remove();
								continue;
							} else {
								iterator.remove();
								map.remove(feature);
								continue;
							}
						}
					}
				} while (iterator.hasNext());
			} while (topFound > 0);
		}
    	
		//Finishes by adding all remaining if no alternate and asks about alternates
		for (IFeature feature : _features.values())
		{
			if (feature.getClass().getAnnotation(Feature.class).hasDisabledCompatility()) 
			{
				Boolean active = configuration.get("Feature Activation", String.format("Feature '%1$s' active", FeatureDataCollector.instance.getFeatureName(feature)), true).getBoolean(true);
				if (active)
				{
					_fullFeatures.add(feature);
					continue;
				}
				else
				{
					_alternateFeatures.add(feature);
					continue;
				}
			}
			else
			{
				_fullFeatures.add(feature);
				continue;
			}
		}
		
		_features.clear();
		//Create a cumulative list of features
		for (IFeature feature : _fullFeatures)
		{
			_methodFeatureStorage.add(feature);
			_features.put(FeatureDataCollector.instance.getFeatureName(feature), feature);
		}
		for (IFeature feature : _alternateFeatures)
		{
			_methodFeatureStorage.add(feature);
			_features.put(FeatureDataCollector.instance.getFeatureName(feature), feature);
		}
		
		//Filling of Booleans and Lists to be filled now
		this.fillData(Data.ALTERNATE, false, _fullFeatures);
		this.fillData(Data.ALTERNATE, true, _alternateFeatures);
		this.fillData(Data.ALTFEATURELIST, _alternateFeatures, _methodFeatureStorage);
		this.fillData(Data.FULLFEATURELIST, _fullFeatures, _methodFeatureStorage);
		this.fillData(Data.FEATUREMAP, _features, _methodFeatureStorage);
		this.fillData(Data.COMPLETEFEATURELIST, _methodFeatureStorage, _methodFeatureStorage);
		
		//Finishing Setup by calling post setup
		for (IFeature feature : _fullFeatures)
		{
			feature.postSetup();
		}
    	
    }

    public void runClient(Configuration configuration){ //TODO
        runSetup(configuration);
        ILogger log = this._logger;

        log.info("Running ClientProxy of all features");

        for (IFeature feature : this._features.values()) {
            log.info("Now running ClientProxy for Feature '%1$s'", FeatureDataCollector.instance.getFeatureName(feature));
            for (Method m : feature.getClass().getDeclaredMethods()) {
                try {
                    FeatureElement data = m.getAnnotation(Feature.FeatureElement.class);
                    if (data.value() == Element.CLIENT)
                    {
                        log.info("Invoking Generic ClientProxy Element of '%1$s'. THIS IS NOT RECCOMENDED. REMOVE THIS IF POSSIBLE.", FeatureDataCollector.instance.getFeatureName(feature));
                        m.invoke(feature);
                        continue;
                    }
                } catch (Throwable e) {
                    this._logger.info("Scanning for and Invoking methods resulted in '%1$s' from feature '%2$s' for feild '%3$s'.", e.toString(), FeatureDataCollector.instance.getFeatureName(feature), m.getName());
                }
            }
        }
    }
    
    public void runPreInitialization(Configuration configuration) 
    {
    	runSetup(configuration);
        ILogger log = this._logger;

        log.info("Running Pre-Initialization of all features");

        for (IFeature feature : this._features.values())
        {
        	log.info("Now Pre-Initializing Feature '%1$s'", FeatureDataCollector.instance.getFeatureName(feature));
            for (Method m : feature.getClass().getDeclaredMethods())
            {
            	try 
            	{
					FeatureElement data = m.getAnnotation(Feature.FeatureElement.class);
					if (data.value() == Element.CONFIGURATION)
					{
						log.info("Invoking Configuration Element of '%1$s'", FeatureDataCollector.instance.getFeatureName(feature));
						m.invoke(feature, FeatureConfigurationProvider.getFeatureConfigurationForFeature(feature, configuration));
						continue;
					}
					if (data.value() == Element.OBJECT)
					{
						log.info("Invoking Object Element of '%1$s'", FeatureDataCollector.instance.getFeatureName(feature));
						this._featureObjectRegisters.put(feature, (FeatureObjectRegister) m.invoke(feature));
						continue;
					}
					if (data.value() == Element.PREINITIALIZATION)
					{
						log.info("Invoking Generic Pre-Initialization Element of '%1$s'. THIS IS NOT RECCOMENDED. REMOVE THIS IF POSSIBLE.", FeatureDataCollector.instance.getFeatureName(feature));
						m.invoke(feature);
						continue;
					}
				} 
            	catch (Throwable e) 
            	{
					this._logger.info("Scanning for and Invoking methods resulted in '%1$s' from feature '%2$s' for feild '%3$s'.", e.toString(), FeatureDataCollector.instance.getFeatureName(feature), m.getName());
				}
            }
        }
        
        this.fillData(Data.FEATUREOBJECTMAP, _featureObjectRegisters, _features);
        log.info("Now Running Object Registration");
        for (FeatureObjectRegister register : _featureObjectRegisters.values())
        {
        	register.registerToGame();
        }
        
        log.info("Finished Running Pre-Initialization of all features");
    }

    public void runInitialization() 
    {
        ILogger log = this._logger;

        log.info("Running Initialization of all features");

        for (IFeature feature : this._features.values())
        {
            Class c = feature.getClass();
            for (Method m : c.getDeclaredMethods())
            {
            	try 
            	{
					FeatureElement data = m.getAnnotation(Feature.FeatureElement.class);
					if (data.value() == Element.ENTITY)
					{
						log.info("Invoking Entity Element of '%1$s'", FeatureDataCollector.instance.getFeatureName(feature));
						m.invoke(feature);
						continue;
					}
					if (data.value() == Element.EVENTBUS_EVENT)
					{
						log.info("Invoking Event Bus - EVENT Element of '%1$s'", FeatureDataCollector.instance.getFeatureName(feature));
						m.invoke(feature);
						continue;
					}
					if (data.value() == Element.EVENTBUS_ORE)
					{
						log.info("Invoking Event Bus - ORE Element of '%1$s'", FeatureDataCollector.instance.getFeatureName(feature));
						m.invoke(feature);
						continue;
					}
					if (data.value() == Element.EVENTBUS_TERRAIN)
					{
						log.info("Invoking Event Bus - TERRAIN Element of '%1$s'", FeatureDataCollector.instance.getFeatureName(feature));
						m.invoke(feature);
						continue;
					}
					if (data.value() == Element.INTITIALIZATION)
					{
						log.info("Invoking Generic Initialization Element of '%1$s'. THIS IS NOT RECCOMENDED. REMOVE THIS IF POSSIBLE.", FeatureDataCollector.instance.getFeatureName(feature));
						m.invoke(feature);
						continue;
					}
				} 
            	catch (Throwable e) 
            	{
					this._logger.info("Scanning for and Invoking methods resulted in '%1$s' from feature '%2$s' for feild '%3$s'.", e.toString(), FeatureDataCollector.instance.getFeatureName(feature), m.getName());
				}
            }
        }
        
        log.info("Now Running Object Crafting Registration");
        for (FeatureObjectRegister register : _featureObjectRegisters.values())
        {
        	register.registerCrafting();
        }
        
        log.info("Finished Running Initialization of all features");
    }

    public void runPostInitialization() 
    {
        ILogger log = this._logger;

        log.info("Running Post-Initialization of all features");

        for (IFeature feature : this._features.values())
        {
            Class c = feature.getClass();
            for (Method m : c.getDeclaredMethods())
            {
            	try 
            	{
					FeatureElement data = m.getAnnotation(Feature.FeatureElement.class);
					if (data.value() == Element.MOD_COMPATIBILITY)
					{
						log.info("Invoking Mod Compatibility Element of '%1$s'", FeatureDataCollector.instance.getFeatureName(feature));
						m.invoke(feature);
						continue;
					}
					if (data.value() == Element.POSTINITIALIZATION)
					{
						log.info("Invoking Generic POST-Initialization Element of '%1$s'. THIS IS NOT RECCOMENDED. REMOVE THIS IF POSSIBLE.", FeatureDataCollector.instance.getFeatureName(feature));
						m.invoke(feature);
						continue;
					}
				} 
            	catch (Throwable e) 
            	{
					this._logger.info("Scanning for and Invoking methods resulted in '%1$s' from feature '%2$s' for feild '%3$s'.", e.toString(), FeatureDataCollector.instance.getFeatureName(feature), m.getName());
				}
            }
        }
        
        log.info("Now Running Object Forge Ore Dictionary Registration");
        for (FeatureObjectRegister register : _featureObjectRegisters.values())
        {
        	register.registerForgeOreDict();
        }
        
        log.info("Finished Running Post-Initialization of all features");
    }
    
    //TODO: Other Startup Events
    
    private <T> void fillData(Feature.FeatureData.Data element, T fill, HashMap<String, IFeature> list)
    {
    	for (IFeature feature: list.values())
    	{
    		for (Field f : feature.getClass().getFields())
    		{
    			try
    			{
    				FeatureData data = f.getAnnotation(Feature.FeatureData.class);
    				if (data.value() == element)
    				{
    					f.set(feature, fill);
    				}
    			}
    			catch (Throwable e)
    			{
    				this._logger.info("Scanning for feilds resulted in '%1$s' from feature '%2$s' for feild '%3$s' from declared class '%4$s'.", e.toString(), FeatureDataCollector.instance.getFeatureName(feature), f.getName(), f.getClass().getPackage());
    			}	
    		}
    	}
    }
    
    private <T> void fillData(Feature.FeatureData.Data element, T fill, ArrayList<IFeature> list)
    {
    	for (IFeature feature: list)
    	{
    		for (Field f : feature.getClass().getFields())
    		{
    			try
    			{
    				FeatureData data = f.getAnnotation(Feature.FeatureData.class);
    				if (data.value() == element)
    				{
    					f.set(feature, fill);
    				}
    			}
    			catch (Throwable e)
    			{
    				this._logger.info("Scanning for feilds resulted in '%1$s' from feature '%2$s' for feild '%3$s' from declared class '%4$s'.", e.toString(), FeatureDataCollector.instance.getFeatureName(feature), f.getName(), f.getClass().getPackage());
    			}	
    		}
    	}
    }
}

