package com.youzan.nsq.client.configs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzan.dcc.client.ConfigClient;
import com.youzan.dcc.client.ConfigClientBuilder;
import com.youzan.dcc.client.entity.config.Config;
import com.youzan.dcc.client.entity.config.ConfigRequest;
import com.youzan.dcc.client.entity.config.interfaces.IResponseCallback;
import com.youzan.dcc.client.exceptions.ConfigParserException;
import com.youzan.dcc.client.exceptions.InvalidConfigException;
import com.youzan.dcc.client.util.inetrfaces.ClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * DCCConfigAccessAgent, which send config request to configs remote with configs client configs in configClient.properties
 * Created by lin on 16/10/26.
 */
public class DCCConfigAccessAgent extends ConfigAccessAgent {
    private static final Logger logger = LoggerFactory.getLogger(DCCConfigAccessAgent.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    //app value configs client need to specify to fetch lookupd config from configs
    //property urls to configs remote
    private static final String NSQ_DCCCONFIG_URLS = "nsq.dcc.%s.urls";
    //property of backup file path
    private static final String NSQ_DCCCONFIG_BACKUP_PATH = "nsq.dcc.backupPath";

    //configs client configs values
    private static ConfigClient dccClient;
    private static String[] urls;
    private static String backupPath;
    private static String env;

    public DCCConfigAccessAgent() throws IOException {
        initClientConfig();
        ClientConfig dccConfig = new ClientConfig();
        //setup client config
        DCCConfigAccessAgent.dccClient = ConfigClientBuilder.create()
                .setRemoteUrls(urls)
                .setBackupFilePath(backupPath)
                .setConfigEnvironment(env)
                .setClientConfig(dccConfig)
                .build();
    }

    public static String[] getUrls() {
        return DCCConfigAccessAgent.urls;
    }

    public static String getBackupPath() {
        return DCCConfigAccessAgent.backupPath;
    }

    public static String getEnv() {
        return DCCConfigAccessAgent.env;
    }

    /**
     * simply extract values of config in to String array
     *
     * @param list updated configs from configs subscribe
     * @return {@link SortedMap presenting mapping between keys and config values}
     */
    private static SortedMap<String, String> extractValues(final List<Config> list) {
        SortedMap<String, String> configMap = new TreeMap<>();
        for (Config config : list) {
            try {
                JsonNode node = mapper.readTree(config.getContent());
                JsonNode valueNode = node.get("value");
                if (valueNode.isArray()) {
                    //has subkeys
                    for (JsonNode val : valueNode) {
                        configMap.put(val.get("key").asText(),
                                val.get("value").asText());
                    }
                } else {
                    //simple value
                    configMap.put(valueNode.get("key").asText(),
                            valueNode.get("value").asText());
                }
            } catch (IOException | InvalidConfigException e) {
                logger.error("Invalid config content in config list.");
            }
        }
        return configMap;
    }

    @Override
    public SortedMap<String, String> handleSubscribe(AbstractConfigAccessDomain domain, AbstractConfigAccessKey[] keys, final IConfigAccessCallback callback) {
        if (keys.length > 1)
            throw new IllegalArgumentException("DCCConfigAccessAgent does not accept more than one key(consumer or producer).");
        if (null == domain || keys.length == 0)
            return null;
        List<ConfigRequest> requests = new ArrayList<>();
        //create config requests out of pass in domain(app) and keys(keys)
        for (AbstractConfigAccessKey key : keys) {
            ConfigRequest request = null;
            try {
                request = (ConfigRequest) ConfigRequest.create(dccClient)
                        .setApp(domain.toDomain())
                        .setKey(key.toKey())
                        .build();
            } catch (ConfigParserException e) {
                logger.warn("Fail to parse config. {}", e.getContentInProblem(), e);
            }
            if (null != request)
                requests.add(request);
        }

        //subscribe
        long start = System.currentTimeMillis();
        try {
            List<Config> firstList = dccClient.subscribe(new IResponseCallback() {
                @Override
                public void onChanged(List<Config> list) throws Exception {
                    SortedMap<String, String> map = extractValues(list);
                    callback.process(map);
                }

                @Override
                public void onFailed(List<Config> list, Exception e) throws Exception {
                    SortedMap<String, String> map = extractValues(list);
                    callback.fallback(map, e);
                }
            }, requests);
            return extractValues(firstList);
        }finally {
            if(logger.isDebugEnabled())
                logger.debug("Time eclapse: {} millisec in getting subscribe response from config access remote.", System.currentTimeMillis() - start);
        }
    }

    @Override
    protected void kickoff() {
        //no need to kick off.
    }

    @Override
    public void close() {
        //TODO: close configs client
    }

    @Override
    public String metadata() {
        StringBuilder sb = new StringBuilder(DCCConfigAccessAgent.class.getName() + "\n");
        String urlStr = "";
        for(String aUrl:urls)
            urlStr += aUrl + ";";
        sb.append("\turls: [").append(urlStr).append("]\n")
                .append("\tenv: [").append(env).append("]\n")
                .append("\tbackupPath: [").append(backupPath).append("]\n");
        return sb.toString();
    }

    /**
     * initialize config client properties.
     *
     * @throws IOException
     */
    private static void initClientConfig() throws IOException {
        //1.fixed properties initialization
        //1.1 config app
//        String app = props.getProperty(NSQ_APP_VAL_PRO);
//        if (null != app)
//            NSQ_APP_VAL = app;
//        else
//            NSQ_APP_VAL = DEFAULT_NSQ_APP_VAL;
//        logger.info("{}:{}", NSQ_APP_VAL_PRO, NSQ_APP_VAL);

        //1.2 config client backup file
        backupPath = props.getProperty(NSQ_DCCCONFIG_BACKUP_PATH);

        String customizedBackup = ConfigAccessAgent.getConfigAccessAgentBackupPath();
        if(null != customizedBackup && !customizedBackup.isEmpty()){
            logger.info("initialize backupPath with user specified value {}.", customizedBackup);
            backupPath = customizedBackup;
        }
        assert null != backupPath;
        logger.info("configs backup path: {}", backupPath);

        //1.3 nsq sdk env, which is also the env of nsq sdk
        env = props.getProperty(NSQ_DCCCONFIG_ENV);
        String customizedEnv = ConfigAccessAgent.getEnv();
        if(null != customizedEnv && !customizedEnv.isEmpty()){
            logger.info("initialize config access remote urls with user specified value {}.",customizedEnv);
            env = customizedEnv;
        }
        assert null != env;

        urls = ConfigAccessAgent.getConfigAccessRemotes();
        if(null == urls || urls.length == 0) {
            //1.4 config server urls, initialized based on sdk env
            String urlsKey = String.format(NSQ_DCCCONFIG_URLS, env);
            urls = props.getProperty(urlsKey)
                    .split(",");
        }
        assert null != urls;

    }
}
