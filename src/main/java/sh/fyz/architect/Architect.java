package sh.fyz.architect;

import sh.fyz.architect.cache.RedisCredentials;
import sh.fyz.architect.cache.RedisManager;
import sh.fyz.architect.persistant.DatabaseCredentials;
import sh.fyz.architect.persistant.SessionManager;

public class Architect {

    private RedisCredentials redisCredentials;
    private DatabaseCredentials databaseCredentials;

    private boolean isReceiver = true;

    public Architect() {
    }

    public Architect setReceiver(boolean isReceiver) {
        this.isReceiver = isReceiver;
        return this;
    }

    public Architect setRedisCredentials(RedisCredentials redisCredentials) {
        this.redisCredentials = redisCredentials;
        return this;
    }

    public Architect setDatabaseCredentials(DatabaseCredentials databaseCredentials) {
        this.databaseCredentials = databaseCredentials;
        return this;
    }

    public void start(){
        System.out.println("Starting Architect!");
        if(redisCredentials != null){
            System.out.println("Connecting to Redis at " + redisCredentials.getHost() + ":" + redisCredentials.getPort());
            RedisManager.initialize(redisCredentials.getHost(), redisCredentials.getPassword(), redisCredentials.getPort(), redisCredentials.getTimeout(), redisCredentials.getMaxConnections(), isReceiver);

        }
        if(databaseCredentials != null){
            System.out.println("Connecting to Database (using "+ databaseCredentials.getSQLAuthProvider().getClass().getSimpleName()+") at " + databaseCredentials.getSQLAuthProvider().getUrl());
            if (isReceiver) {
                System.out.println("/!\\ This instance will be used as a receiver /!\\");
            }
            SessionManager.initialize(databaseCredentials.getSQLAuthProvider(),
                    databaseCredentials.getUser(), databaseCredentials.getPassword(), databaseCredentials.getPoolSize());
        } else {
            SessionManager.initialize(null, null, null, 1);
        }
    }

    public void stop() {
        System.out.println("Stopping Architect!");
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if(redisCredentials != null){
            System.out.println("Disconnecting from Redis at " + redisCredentials.getHost() + ":" + redisCredentials.getPort());
            RedisManager.get().shutdown();
        }

        if(databaseCredentials != null){
            System.out.println("Disconnecting from Database at " + databaseCredentials.getSQLAuthProvider().getUrl());
            SessionManager.get().close();
        }
    }


}
