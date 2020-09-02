package com.jd.blockchain.kvdb.engine.rocksdb;

import com.jd.blockchain.binaryproto.DataContractRegistry;
import com.jd.blockchain.kvdb.engine.Config;
import com.jd.blockchain.kvdb.engine.KVDBInstance;
import com.jd.blockchain.kvdb.protocol.proto.wal.Entity;
import com.jd.blockchain.kvdb.protocol.proto.wal.EntityCoder;
import com.jd.blockchain.kvdb.protocol.proto.wal.KV;
import com.jd.blockchain.kvdb.protocol.proto.wal.KVItem;
import com.jd.blockchain.kvdb.protocol.proto.wal.WalEntity;
import com.jd.blockchain.utils.Bytes;
import com.jd.blockchain.utils.io.FileUtils;
import com.jd.blockchain.wal.FileLogger;
import com.jd.blockchain.wal.WalConfig;
import com.jd.blockchain.wal.WalIterator;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.Cache;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.IndexType;
import org.rocksdb.LRUCache;
import org.rocksdb.MutableColumnFamilyOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.rocksdb.util.SizeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

public class RocksDBProxy extends KVDBInstance {

    static {
        DataContractRegistry.register(KV.class);
        DataContractRegistry.register(Entity.class);
    }

    private static Logger LOGGER = LoggerFactory.getLogger(RocksDBProxy.class);

    private WriteOptions writeOptions;

    private RocksDB db;

    protected String path;

    private FileLogger<Entity> wal;

    // 是否需要执行wal redo操作
    private volatile boolean needRedo = true;

    public String getPath() {
        return path;
    }

    private RocksDBProxy(RocksDB db, String path) {
        this(db, path, null);
    }

    private RocksDBProxy(RocksDB db, String path, FileLogger<Entity> wal) {
        this.db = db;
        this.path = path;
        this.wal = wal;
        this.writeOptions = initWriteOptions();

    }

    private static WriteOptions initWriteOptions() {
        WriteOptions options = new WriteOptions();
        options.setDisableWAL(true);
        options.setNoSlowdown(false);
        return options;
    }

    private static Options initDBOptions() {
        Cache cache = new LRUCache(512 * SizeUnit.MB, 64, false);

        final BlockBasedTableConfig tableOptions = new BlockBasedTableConfig()
                .setBlockCache(cache)
                .setMetadataBlockSize(4096)
                .setCacheIndexAndFilterBlocks(true) // 设置索引和布隆过滤器使用Block Cache内存
                .setCacheIndexAndFilterBlocksWithHighPriority(true)
                .setIndexType(IndexType.kTwoLevelIndexSearch) // 设置两级索引，控制索引占用内存
                .setPinTopLevelIndexAndFilter(false)
                .setBlockSize(4096)
                .setFilterPolicy(null) // 不设置布隆过滤器
                ;

        Options options = new Options()
                // 最多占用256 * 6 + 512 = 2G内存
                .setWriteBufferSize(256 * SizeUnit.MB)
                .setMaxWriteBufferNumber(6)
                .setMinWriteBufferNumberToMerge(2)
                .setMaxOpenFiles(100) // 控制最大打开文件数量，防止内存持续增加
                .setAllowConcurrentMemtableWrite(true) //允许并行Memtable写入
                .setCreateIfMissing(true)
                .setTableFormatConfig(tableOptions)
                .setMaxBackgroundCompactions(5)
                .setMaxBackgroundFlushes(4);
        return options;
    }

    private static MutableColumnFamilyOptions initColumnFamilyOptions() {
        return MutableColumnFamilyOptions.builder()
                .setWriteBufferSize(32 * 1024 * 1024)
                .setMaxWriteBufferNumber(4)
                .build();
    }

    private static void initDB(RocksDB db) throws RocksDBException {
        ColumnFamilyHandle defaultColumnFamily = db.getDefaultColumnFamily();
        db.setOptions(defaultColumnFamily, initColumnFamilyOptions());
    }

    public static RocksDBProxy open(String path) throws RocksDBException {
        return open(path, null);
    }

    public static RocksDBProxy open(String path, Config config) throws RocksDBException {
        LOGGER.info("db [{}] wal config: {}", path, null != config ? config.toString() : "null");
        RocksDB db = RocksDB.open(initDBOptions(), path);
        initDB(db);
        try {
            RocksDBProxy instance;
            if (null == config || config.isWalDisable()) {
                instance = new RocksDBProxy(db, path);
            } else {
                WalConfig walConfig = new WalConfig(config.getWalpath(), config.getWalFlush(), true);
                instance = new RocksDBProxy(db, path, new FileLogger(walConfig, EntityCoder.getInstance()));
            }

            instance.checkAndRedoWal();

            return instance;
        } catch (IOException e) {
            throw new RocksDBException(e.toString());
        }
    }

    protected void write(WriteBatch batch) throws RocksDBException {
        db.write(writeOptions, batch);
    }

    // 检查wal日志进行数据一致性检查
    private void checkAndRedoWal() throws RocksDBException {
        if (!needRedo) {
            return;
        } else {
            if (wal != null) {
                synchronized (wal) {
                    WalIterator<Entity> iterator = wal.forwardIterator();
                    try {
                        if (iterator.hasNext()) {

                            LOGGER.info("redo wal...");
                            while (iterator.hasNext()) {
                                Entity e = iterator.next();
                                WriteBatch batch = new WriteBatch();
                                for (KV kv : e.getKVs()) {
                                    batch.put(kv.getKey(), kv.getValue());
                                }
                                db.write(writeOptions, batch);
                            }
                            wal.checkpoint();
                            needRedo = false;
                            LOGGER.info("redo wal complete");
                        }
                    } catch (Exception e) {
                        LOGGER.error("redo wal exception", e);
                        throw new RocksDBException("wal redo error!");
                    }
                }
            }
        }
    }

    @Override
    public byte[] get(byte[] key) throws RocksDBException {
        checkAndRedoWal();
        return db.get(key);
    }

    @Override
    public void set(byte[] key, byte[] value) throws RocksDBException {
        if (null != wal) {
            synchronized (wal) {
                try {
                    wal.append(WalEntity.newPutEntity(new KVItem(key, value)));
                } catch (IOException e) {
                    LOGGER.error("single kv set, wal append error!", e);
                    throw new RocksDBException(e.toString());
                }
                try {
                    db.put(writeOptions, key, value);
                } catch (RocksDBException e) {
                    needRedo = true;
                    LOGGER.error("single kv set error!", e);
                    throw e;
                }
                try {
                    wal.checkpoint();
                } catch (IOException e) {
                    LOGGER.error("single kv set, wal checkpoint error!", e);
                }
            }
        } else {
            db.put(writeOptions, key, value);
        }
    }

    @Override
    public void batchSet(Map<Bytes, byte[]> kvs) throws RocksDBException {
        WriteBatch batch = new WriteBatch();
        KVItem[] walkvs = new KVItem[kvs.size()];
        int i = 0;
        for (Map.Entry<Bytes, byte[]> entry : kvs.entrySet()) {
            byte[] key = entry.getKey().toBytes();
            batch.put(key, entry.getValue());
            walkvs[i] = new KVItem(key, entry.getValue());
            i++;
        }
        if (null != wal) {
            synchronized (wal) {
                try {
                    wal.append(WalEntity.newPutEntity(walkvs));
                } catch (IOException e) {
                    LOGGER.error("batch commit, wal append error!", e);
                    throw new RocksDBException(e.toString());
                }
                try {
                    if (kvs.size() > 1) {
                        db.write(writeOptions, batch);
                    } else {
                        db.put(walkvs[0].getKey(), walkvs[0].getValue());
                    }
                } catch (RocksDBException e) {
                    needRedo = true;
                    LOGGER.error("batch commit error!", e);
                    throw e;
                }
                try {
                    wal.checkpoint();
                } catch (IOException e) {
                    LOGGER.error("batch commit, wal checkpoint error!", e);
                }
            }
        } else {
            try {
                if (kvs.size() > 1) {
                    db.write(writeOptions, batch);
                } else {
                    db.put(walkvs[0].getKey(), walkvs[0].getValue());
                }
            } catch (Exception e) {
                LOGGER.error("batch commit error!", e);
                throw e;
            }
        }
    }

    @Override
    public void close() {
        if (null != wal) {
            try {
                wal.close();
            } catch (IOException e) {
                LOGGER.error("Error occurred while closing wal[" + path + "]", e);
            }
        }
        if (db != null) {
            try {
                db.close();
            } catch (Exception e) {
                LOGGER.error("Error occurred while closing rocksdb[" + path + "]", e);
            } finally {
                db = null;
            }
        }
    }

    @Override
    public synchronized void drop() {
        if (null != wal) {
            try {
                wal.close();
            } catch (IOException e) {
                LOGGER.error("Error occurred while closing wal[" + path + "]", e);
            }
        }
        if (db != null) {
            try {
                close();
                FileUtils.deleteFile(path);
            } catch (Exception e) {
                LOGGER.error("Error occurred while dropping rocksdb[" + path + "]", e);
            }
        }
    }

}
