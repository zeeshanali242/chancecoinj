import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;

import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.AddressFormatException;
import com.google.bitcoin.core.Block;
import com.google.bitcoin.core.BlockChain;
import com.google.bitcoin.core.DumpedPrivateKey;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.InsufficientMoneyException;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.PeerGroup;
import com.google.bitcoin.core.PeerGroup.FilterRecalculateMode;
import com.google.bitcoin.core.Transaction.SigHash;
import com.google.bitcoin.core.ScriptException;
import com.google.bitcoin.core.Sha256Hash;
import com.google.bitcoin.core.StoredBlock;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.TransactionInput;
import com.google.bitcoin.core.TransactionOutPoint;
import com.google.bitcoin.core.TransactionOutput;
import com.google.bitcoin.core.UnsafeByteArrayOutputStream;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.crypto.TransactionSignature;
import com.google.bitcoin.net.discovery.DnsDiscovery;
import com.google.bitcoin.params.MainNetParams;
import com.google.bitcoin.script.Script;
import com.google.bitcoin.script.ScriptBuilder;
import com.google.bitcoin.script.ScriptChunk;
import com.google.bitcoin.script.ScriptOpCodes;
import com.google.bitcoin.store.BlockStore;
import com.google.bitcoin.store.BlockStoreException;
import com.google.bitcoin.store.H2FullPrunedBlockStore;
import com.google.bitcoin.wallet.WalletTransaction;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.sun.org.apache.xpath.internal.compiler.OpCodes;

public class Blocks implements Runnable {
	public NetworkParameters params;
	public Logger logger = LoggerFactory.getLogger(Blocks.class);
	private static Blocks instance = null;
	public Wallet wallet;
	public String walletFile = "resources/db/wallet";
	public PeerGroup peerGroup;
	public BlockChain blockChain;
	public BlockStore blockStore;
	public Boolean working = false;
	public Boolean parsing = false;
	public Boolean initializing = false;
	public Boolean initialized = false;
	public Integer parsingBlock = 0;
	public Integer versionCheck = 0;
	public Integer bitcoinBlock = 0;
	public Integer chancecoinBlock = 0;
	public Double priceBTC = 0.0;
	public Double priceCHA = 0.0;
	public String statusMessage = "";

	public static Blocks getInstanceSkipVersionCheck() {
		if(instance == null) {
			instance = new Blocks();
		} 
		return instance;
	}

	public static Blocks getInstanceFresh() {
		if(instance == null) {
			instance = new Blocks();
			instance.versionCheck();
		} 
		return instance;
	}

	public static Blocks getInstanceAndWait() {
		if(instance == null) {
			instance = new Blocks();
			instance.versionCheck();
			instance.init();
		} 
		instance.follow();
		return instance;
	}

	public static Blocks getInstance() {
		if(instance == null) {
			instance = new Blocks();
			instance.versionCheck();
			new Thread() { public void run() {instance.init();}}.start();
		} 
		if (!instance.working && instance.initialized) {
			new Thread() { public void run() {instance.follow();}}.start();
		}
		return instance;
	}

	public void versionCheck() {
		versionCheck(true);
	}
	public void versionCheck(Boolean autoUpdate) {
		Integer minMajorVersion = Util.getMinMajorVersion();
		Integer minMinorVersion = Util.getMinMinorVersion();
		if (Config.majorVersion<minMajorVersion || (Config.majorVersion.equals(minMajorVersion) && Config.minorVersion<minMinorVersion)) {
			if (autoUpdate) {
				statusMessage = "Version is out of date, updating now"; 
				logger.info(statusMessage);
				try {
					Runtime.getRuntime().exec("java -jar update/update.jar");
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			} else {
				logger.info("Version is out of date. Please upgrade to version "+Util.getMinVersion()+".");
			}
			System.exit(0);
		}
	}

	@Override
	public void run() {
		while (true) {
			logger.info("Looping blocks");
			Blocks.getInstance();
			try {
				Thread.sleep(1000*60); //once a minute, we run blocks.follow()
			} catch (InterruptedException e) {
				logger.error("Error during loop: "+e.toString());
			}
		}
	}

	public void init() {
		if (!initializing) {
			initializing = true;
			Locale.setDefault(new Locale("en", "US"));
			params = MainNetParams.get();
			try {
				if ((new File(walletFile)).exists()) {
					statusMessage = "Found wallet file"; 
					logger.info(statusMessage);
					wallet = Wallet.loadFromFile(new File(walletFile));
				} else {
					statusMessage = "Creating new wallet file"; 
					logger.info(statusMessage);
					wallet = new Wallet(params);
					ECKey newKey = new ECKey();
					newKey.setCreationTimeSeconds(Config.burnCreationTime);
					wallet.addKey(newKey);
				}
				String fileBTCdb = Config.dbPath+Config.appName.toLowerCase()+".h2.db";
				Database db = Database.getInstance();

				//reasons to redownload:
				ResultSet rsPokerBets = db.executeQuery("select * from bets where cards is not null;");
				ResultSet rsRolls = db.executeQuery("select * from rolls;");
				Boolean shouldReDownload = !new File(fileBTCdb).exists() || !rsPokerBets.next() || !rsRolls.next();

				if (shouldReDownload) {
					deleteDatabases();
					statusMessage = "Downloading BTC database"; 
					logger.info(statusMessage);
					Util.downloadToFile(Config.downloadUrl+Config.appName.toLowerCase()+".h2.db", fileBTCdb);
					logger.info("Finished downloading BTC database");
					String fileCHAdb = Database.dbFile;	
					statusMessage = "Downloading CHA database"; 
					logger.info(statusMessage);
					Util.downloadToFile(Config.downloadUrl+Config.appName.toLowerCase()+"-"+Config.majorVersionDB.toString()+".db", fileCHAdb);
					logger.info("Finished downloading CHA database");
					Database.getInstance().init();
				}
				statusMessage = "Downloading Bitcoin blocks";
				blockStore = new H2FullPrunedBlockStore(params, Config.dbPath+Config.appName.toLowerCase(), 2000);
				blockChain = new BlockChain(params, wallet, blockStore);
				peerGroup = new PeerGroup(params, blockChain);

				//check the block heights
				Integer blockHeight = getHeight();
				Integer lastBlock = Util.getLastBlock();
				bitcoinBlock = blockHeight;
				chancecoinBlock = lastBlock;

				peerGroup.addWallet(wallet);
				peerGroup.setFastCatchupTimeSecs(Config.burnCreationTime);
				wallet.autosaveToFile(new File(walletFile), 1, TimeUnit.MINUTES, null);
				peerGroup.addPeerDiscovery(new DnsDiscovery(params));
				peerGroup.startAndWait();
				peerGroup.addEventListener(new ChancecoinPeerEventListener());
				peerGroup.downloadBlockChain();
				while (!hasChainHead()) {
					try {
						logger.info("Blockstore doesn't yet have a chain head, so we are sleeping.");
						Thread.sleep(1000);
					} catch (InterruptedException e) {
					}
				}
			} catch (Exception e) {
				logger.error("Error during init: "+e.toString());
				e.printStackTrace();
				deleteDatabases();
				initialized = false;
				initializing = false;
				init();
			}
			initialized = true;
			initializing = false;
		}
	}

	public void deleteDatabases() {
		logger.info("Deleting Bitcoin and Chancecoin databases");
		String fileBTCdb = Config.dbPath+Config.appName.toLowerCase()+".h2.db";
		new File(fileBTCdb).delete();
		String fileCHAdb = Database.dbFile;
		new File(fileCHAdb).delete();
	}

	public Boolean hasChainHead() {
		try {
			Integer blockHeight = blockStore.getChainHead().getHeight();
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	public void follow() {
		follow(false);
	}
	public void follow(Boolean force) {
		logger.info("Working status: "+working);
		if ((!working && initialized) || force) {
			statusMessage = "Checking block height";
			logger.info(statusMessage);
			working = true;
			try {
				//catch Chancecoin up to Bitcoin
				Integer blockHeight = getHeight();
				Integer lastBlock = Util.getLastBlock();
				bitcoinBlock = blockHeight;
				chancecoinBlock = lastBlock;

				if (chancecoinBlock>0 && bitcoinBlock>0 && Config.redownloadDatabase>0 && chancecoinBlock < bitcoinBlock-Config.redownloadDatabase) { 
					//if more than 144 blocks out of date (about a day), we re-download the databases
					deleteDatabases();
					initialized = false;
					initializing = false;
					working = false;
					init();					
				} else {

					//get BTC, CHA prices
					try {
						priceCHA = Util.getTradesPoloniex().get(0).rate;
						priceBTC = Util.getBTCPrice();
					} catch (Exception e) {
					}

					Integer nextBlock = lastBlock + 1;

					chancecoinBlock = lastBlock;
					logger.info("Bitcoin block height: "+blockHeight);	
					logger.info("Chancecoin block height: "+lastBlock);
					if (lastBlock < blockHeight) {
						//traverse new blocks
						parsing = true;
						Database db = Database.getInstance();
						Integer blocksToScan = blockHeight - lastBlock;
						List<Sha256Hash> blockHashes = new ArrayList<Sha256Hash>();

						Block block = peerGroup.getDownloadPeer().getBlock(blockStore.getChainHead().getHeader().getHash()).get(30, TimeUnit.SECONDS);
						while (blockStore.get(block.getHash()).getHeight()>lastBlock) {
							blockHashes.add(block.getHash());
							block = blockStore.get(block.getPrevBlockHash()).getHeader();
						}

						for (int i = blockHashes.size()-1; i>=0; i--) { //traverse blocks in reverse order
							block = peerGroup.getDownloadPeer().getBlock(blockHashes.get(i)).get(30, TimeUnit.SECONDS);
							blockHeight = blockStore.get(block.getHash()).getHeight();
							chancecoinBlock = blockHeight;
							statusMessage = "Catching Chancecoin up to Bitcoin "+Util.format((blockHashes.size() - i)/((double) blockHashes.size())*100.0)+"%";	
							logger.info("Catching Chancecoin up to Bitcoin (block "+blockHeight.toString()+"): "+Util.format((blockHashes.size() - i)/((double) blockHashes.size())*100.0)+"%");	
							importBlock(block, blockHeight);
						}

						if (getDBMinorVersion()<Config.minorVersionDB){
							reparse(true);
							db.updateMinorVersion();		    	
						}else{
							parseFrom(nextBlock, true);
						}
						parsing = false;
					}
					Order.expire();
				}
			} catch (Exception e) {
				logger.error("Error during follow: "+e.toString());
				e.printStackTrace();
			}	
			working = false;
		}
	}

	public void reDownloadBlockTransactions(Integer blockHeight) {
		Database db = Database.getInstance();
		ResultSet rs = db.executeQuery("select * from blocks where block_index='"+blockHeight.toString()+"';");
		try {
			if (rs.next()) {
				Block block = peerGroup.getDownloadPeer().getBlock(new Sha256Hash(rs.getString("block_hash"))).get();
				db.executeUpdate("delete from transactions where block_index='"+blockHeight.toString()+"';");
				for (Transaction tx : block.getTransactions()) {
					importTransaction(tx, block, blockHeight);
				}
			}
		} catch (Exception e) {
			logger.error(e.toString());
		}
	}

	public void importBlock(Block block, Integer blockHeight) {
		statusMessage = "Importing block "+blockHeight;
		logger.info(statusMessage);
		Database db = Database.getInstance();
		ResultSet rs = db.executeQuery("select * from blocks where block_hash='"+block.getHashAsString()+"';");
		try {
			if (!rs.next()) {
				db.executeUpdate("INSERT INTO blocks(block_index,block_hash,block_time) VALUES('"+blockHeight.toString()+"','"+block.getHashAsString()+"','"+block.getTimeSeconds()+"')");
			}
			for (Transaction tx : block.getTransactions()) {
				importTransaction(tx, block, blockHeight);
			}
			Order.expire();
			Bet.resolve();
		} catch (SQLException e) {
		}
	}

	public List<Byte> scriptToDataArrayList(Script script) {
		return scriptToDataArrayList(script, null);
	}
	public List<Byte> scriptToDataArrayList(Script script, List<Byte> dataArrayList) {
		if (dataArrayList == null) {
			dataArrayList = new ArrayList<Byte>(); 
		}
		List<ScriptChunk> asm = script.getChunks();
		if (asm.size()==2 && asm.get(0).equalsOpCode(106)) { //OP_RETURN
			for (byte b : asm.get(1).data) dataArrayList.add(b);
		} else if (asm.size()>=5 && asm.get(0).equalsOpCode(81) && asm.get(3).equalsOpCode(82) && asm.get(4).equalsOpCode(174)) { //MULTISIG
			for (int i=1; i<asm.get(2).data[0]+1; i++) dataArrayList.add(asm.get(2).data[i]);
		}
		return dataArrayList;
	}

	public byte[] dataArrayListToDataArray(List<Byte> dataArrayList) {
		byte[] data = null;
		byte[] prefixBytes = Config.prefix.getBytes();
		byte[] dataPrefixBytes = Util.toByteArray(dataArrayList.subList(0, Config.prefix.length()));
		dataArrayList = dataArrayList.subList(Config.prefix.length(), dataArrayList.size());
		data = Util.toByteArray(dataArrayList);
		if (!Arrays.equals(prefixBytes,dataPrefixBytes)) {
			return null;
		}
		return data;
	}

	public String dataArrayToString(byte[] data) {
		String dataString = "";
		try {
			dataString = new String(data,"ISO-8859-1");
		} catch (UnsupportedEncodingException e) {
		}
		return dataString;
	}

	public void importTransaction(Transaction tx, Block block, Integer blockHeight) {
		BigInteger fee = BigInteger.ZERO;
		String destination = "";
		BigInteger btcAmount = BigInteger.ZERO;
		List<Byte> dataArrayList = new ArrayList<Byte>();
		byte[] data = null;
		String source = "";

		Database db = Database.getInstance();

		//check to see if this is a burn
		for (TransactionOutput out : tx.getOutputs()) {
			try {
				Script script = out.getScriptPubKey();
				Address address = script.getToAddress(params);
				if (address.toString().equals(Config.burnAddress)) {
					destination = address.toString();
					btcAmount = out.getValue();
				}
			} catch(ScriptException e) {				
			}
		}

		for (TransactionOutput out : tx.getOutputs()) {
			//fee = fee.subtract(out.getValue()); //TODO, turn this on
			try {
				Script script = out.getScriptPubKey();
				dataArrayList = scriptToDataArrayList(script, dataArrayList);

				if (destination.equals("") && btcAmount==BigInteger.ZERO && dataArrayList.size()==0) {
					Address address = script.getToAddress(params);
					destination = address.toString();
					btcAmount = out.getValue();					
				}
			} catch(ScriptException e) {				
			}
		}
		if (destination.equals(Config.burnAddress)) {
		} else if (dataArrayList.size()>Config.prefix.length()) {
			data = dataArrayListToDataArray(dataArrayList);
			if (data==null) {
				return;
			}
		} else {
			return;
		}
		for (TransactionInput in : tx.getInputs()) {
			if (in.isCoinBase()) return;
			try {
				Script script = in.getScriptSig();
				//fee = fee.add(in.getValue()); //TODO, turn this on
				//Script scriptOutput = in.getParentTransaction().getOutput((int) in.getOutpoint().getIndex()).getScriptPubKey();
				Address address = null;
				//if (scriptOutput.isSentToAddress()) {
				address = script.getFromAddress(params);
				if (source.equals("")) {
					source = address.toString();
				}else if (!source.equals(address.toString()) && !destination.equals(Config.burnAddress)){ //require all sources to be the same unless this is a burn
					return;
				}
			} catch(ScriptException e) {
			}
		}

		logger.info("Incoming transaction from "+source+" to "+destination+" ("+tx.getHashAsString()+")");

		if (!source.equals("") && (destination.equals(Config.burnAddress) || dataArrayList.size()>0)) {
			String dataString = "";
			if (destination.equals(Config.burnAddress)) {
			}else{
				dataString = dataArrayToString(data);
			}
			db.executeUpdate("delete from transactions where tx_hash='"+tx.getHashAsString()+"' and block_index<0");
			ResultSet rs = db.executeQuery("select * from transactions where tx_hash='"+tx.getHashAsString()+"';");
			try {
				if (!rs.next()) {
					if (block!=null) {
						PreparedStatement ps = db.connection.prepareStatement("INSERT INTO transactions(tx_index, tx_hash, block_index, block_time, source, destination, btc_amount, fee, data) VALUES('"+(Util.getLastTxIndex()+1)+"','"+tx.getHashAsString()+"','"+blockHeight+"','"+block.getTimeSeconds()+"','"+source+"','"+destination+"','"+btcAmount.toString()+"','"+fee.toString()+"',?)");
						ps.setString(1, dataString);
						ps.execute();
					}else{
						PreparedStatement ps = db.connection.prepareStatement("INSERT INTO transactions(tx_index, tx_hash, block_index, block_time, source, destination, btc_amount, fee, data) VALUES('"+(Util.getLastTxIndex()+1)+"','"+tx.getHashAsString()+"','-1','','"+source+"','"+destination+"','"+btcAmount.toString()+"','"+fee.toString()+"',?)");
						ps.setString(1, dataString);
						ps.execute();
					}
				}
			} catch (SQLException e) {
				logger.error("Error during import transaction: "+e.toString());
				e.printStackTrace();
			}
		}
	}

	public void reparse() {
		reparse(false);
	}
	public void reparse(final Boolean force) {
		Database db = Database.getInstance();
		db.executeUpdate("delete from debits;");
		db.executeUpdate("delete from credits;");
		db.executeUpdate("delete from balances;");
		db.executeUpdate("delete from sends;");
		db.executeUpdate("delete from orders;");
		db.executeUpdate("delete from order_matches;");
		db.executeUpdate("delete from btcpays;");
		db.executeUpdate("delete from bets;");
		db.executeUpdate("delete from rolls;");
		db.executeUpdate("delete from burns;");
		db.executeUpdate("delete from cancels;");
		db.executeUpdate("delete from order_expirations;");
		db.executeUpdate("delete from order_match_expirations;");
		db.executeUpdate("delete from messages;");
		new Thread() { public void run() {parseFrom(0, force);}}.start();
	}

	public void parseFrom(Integer blockNumber) {
		parseFrom(blockNumber, false);
	}
	public void parseFrom(Integer blockNumber, Boolean force) {
		if (!working || force) {
			parsing = true;
			working = true;
			Database db = Database.getInstance();
			ResultSet rs = db.executeQuery("select * from blocks where block_index>="+blockNumber.toString()+" order by block_index asc;");
			try {
				while (rs.next()) {
					Integer blockIndex = rs.getInt("block_index");
					parseBlock(blockIndex);
					Order.expire(blockIndex);
					Bet.resolve();
				}
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			working = false;
			parsing = false;
		}
	}

	public static List<Byte> getMessageFromTransaction(String txDataString) {
		byte[] data;
		List<Byte> message = null;
		try {
			data = txDataString.getBytes("ISO-8859-1");
			List<Byte> dataArrayList = Util.toByteArrayList(data);

			message = dataArrayList.subList(4, dataArrayList.size());		
			return message;
		} catch (UnsupportedEncodingException e) {
		}
		return message;
	}

	public static List<Byte> getMessageTypeFromTransaction(String txDataString) {
		byte[] data;
		List<Byte> messageType = null;
		try {
			data = txDataString.getBytes("ISO-8859-1");
			List<Byte> dataArrayList = Util.toByteArrayList(data);

			messageType = dataArrayList.subList(0, 4);
			return messageType;
		} catch (UnsupportedEncodingException e) {
		}
		return messageType;
	}	

	public void parseBlock(Integer blockIndex) {
		Database db = Database.getInstance();
		ResultSet rsTx = db.executeQuery("select * from transactions where block_index="+blockIndex.toString()+" order by tx_index asc;");
		parsingBlock = blockIndex;
		statusMessage = "Parsing block "+blockIndex.toString();
		logger.info(statusMessage);
		try {
			while (rsTx.next()) {
				Integer txIndex = rsTx.getInt("tx_index");
				String source = rsTx.getString("source");
				String destination = rsTx.getString("destination");
				BigInteger btcAmount = BigInteger.valueOf(rsTx.getInt("btc_amount"));
				String dataString = rsTx.getString("data");

				if (destination.equals(Config.burnAddress)) {
					//parse Burn
					Burn.parse(txIndex);
				} else {
					List<Byte> messageType = getMessageTypeFromTransaction(dataString);
					List<Byte> message = getMessageFromTransaction(dataString);

					if (messageType!=null && messageType.size()>=4 && message!=null) {
						logger.info("Parsing transaction");
						if (messageType.get(3)==Bet.idDice.byteValue() || messageType.get(3)==Bet.idPoker.byteValue()) {
							Bet.parse(txIndex, message);
						} else if (messageType.get(3)==Send.id.byteValue()) {
							Send.parse(txIndex, message);
						} else if (messageType.get(3)==Order.id.byteValue()) {
							Order.parse(txIndex, message);
						} else if (messageType.get(3)==Cancel.id.byteValue()) {
							Cancel.parse(txIndex, message);
						} else if (messageType.get(3)==BTCPay.id.byteValue()) {
							BTCPay.parse(txIndex, message);
						} else if (messageType.get(3)==Roll.id.byteValue()) {
							Roll.parse(txIndex, message);
							//} else if (messageType.get(3)==Quote.id.byteValue()) {
							//	Quote.parse(txIndex, message);
							//} else if (messageType.get(3)==QuotePay.id.byteValue()) {
							//	QuotePay.parse(txIndex, message);
						}						
					}
				}
			}
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}		
	}

	public void deletePending() {
		Database db = Database.getInstance();
		db.executeUpdate("delete from transactions where block_index<0 and tx_index<(select max(tx_index) from transactions)-10;");
	}


	public Integer getDBMinorVersion() {
		Database db = Database.getInstance();
		ResultSet rs = db.executeQuery("PRAGMA user_version;");
		try {
			while(rs.next()) {
				return rs.getInt("user_version");
			}
		} catch (SQLException e) {
		}	
		return 0;
	}

	public Integer getHeight() {
		try {
			Integer height = blockStore.getChainHead().getHeight();
			return height;
		} catch (BlockStoreException e) {
		}
		return 0;
	}

	public void deleteAddress(String address) throws Exception {
		if (!Config.readOnly) {
			ECKey deleteKey = null;
			String deleteAddress = address;
			for (ECKey key : wallet.getKeys()) {
				if (key.toAddress(params).toString().equals(deleteAddress)) {
					deleteKey = key;
				}
			}
			if (deleteKey != null) {
				logger.info("Deleting private key");
				wallet.removeKey(deleteKey);
				if (wallet.getKeys().size()<=0) {
					ECKey newKey = new ECKey();
					wallet.addKey(newKey);
				}
			} 
		} else {
			throw new Exception("Chancecoin is in read only mode.");			
		}
	}

	public String importPrivateKey(ECKey key) throws Exception {
		if (!Config.readOnly) {
			String address = "";
			logger.info("Importing private key");
			address = key.toAddress(params).toString();
			logger.info("Importing address "+address);
			if (wallet.getKeys().contains(key)) {
				wallet.removeKey(key);
			}
			wallet.addKey(key);
			/*
			try {
				importTransactionsFromAddress(address);
			} catch (Exception e) {
				throw new Exception(e.getMessage());
			}
			 */
			return address;	
		} else {
			throw new Exception("Chancecoin is in read only mode.");			
		}
	}
	public String importPrivateKey(String privateKey) throws Exception {
		DumpedPrivateKey dumpedPrivateKey;
		String address = "";
		ECKey key = null;
		logger.info("Importing private key");
		try {
			dumpedPrivateKey = new DumpedPrivateKey(params, privateKey);
			key = dumpedPrivateKey.getKey();
			return importPrivateKey(key);
		} catch (AddressFormatException e) {
			throw new Exception(e.getMessage());
		}
	}

	/*
	public void importTransactionsFromAddress(String address) throws Exception {
		logger.info("Importing transactions");
		try {
			wallet.addWatchedAddress(new Address(params, address));
		} catch (AddressFormatException e) {
		}
		List<Map.Entry<String,String>> txsInfo = Util.getTransactions(address);
		BigInteger balance = BigInteger.ZERO;
		BigInteger balanceSent = BigInteger.ZERO;
		BigInteger balanceReceived = BigInteger.ZERO;
		Integer transactionCount = 0;
		for (Map.Entry<String,String> txHashBlockHash : txsInfo) {
			String txHash = txHashBlockHash.getKey();
			String blockHash = txHashBlockHash.getValue();
			try {
				Block block = peerGroup.getDownloadPeer().getBlock(new Sha256Hash(blockHash)).get();
				List<Transaction> txs = block.getTransactions();
				for (Transaction tx : txs) {
					if (tx.getHashAsString().equals(txHash)){// && wallet.isPendingTransactionRelevant(tx)) {
						transactionCount ++;
						wallet.receivePending(tx, peerGroup.getDownloadPeer().downloadDependencies(tx).get());
						balanceReceived = balanceReceived.add(tx.getValueSentToMe(wallet));
						balanceSent = balanceSent.add(tx.getValueSentFromMe(wallet));
						balance = balance.add(tx.getValueSentToMe(wallet));
						balance = balance.subtract(tx.getValueSentFromMe(wallet));
					}
				}
			} catch (InterruptedException e) {
				throw new Exception(e.getMessage());
			} catch (ExecutionException e) {				
				throw new Exception(e.getMessage());
			}
		}
		logger.info("Address balance: "+balance);		
	}
	 */

	public Transaction transaction(String source, String destination, BigInteger btcAmount, BigInteger fee, String dataString) throws Exception {
		return transaction(source, destination, btcAmount, fee, dataString, "", -1);
	}
	public Transaction transaction(String source, String destination, BigInteger btcAmount, BigInteger fee, String dataString, String useUnspentTxHash, Integer useUnspentVout) throws Exception {
		List<String> destinations = new ArrayList<String>();
		destinations.add(destination);
		List<BigInteger> btcAmounts = new ArrayList<BigInteger>();
		btcAmounts.add(btcAmount);
		return transaction(source, destinations, btcAmounts, fee, dataString, useUnspentTxHash, useUnspentVout);		
	}
	public Transaction transaction(String source, List<String> destinations, List<BigInteger> btcAmounts, BigInteger fee, String dataString, String useUnspentTxHash, Integer useUnspentVout) throws Exception {
		Transaction tx = new Transaction(params);
		if (destinations.size()==0 || btcAmounts.size()==0) throw new Exception("Please give at least one destination and amount.");
		if (destinations.size()!=btcAmounts.size()) throw new Exception("Please give the same number of destinations as amounts.");
		String destination = destinations.get(0);
		BigInteger btcAmount = btcAmounts.get(0);
		if (destination.equals("") || btcAmount.compareTo(BigInteger.valueOf(Config.dustSize))>=0) {

			byte[] data = null;
			List<Byte> dataArrayList = new ArrayList<Byte>();
			try {
				data = dataString.getBytes("ISO-8859-1");
				dataArrayList = Util.toByteArrayList(data);
			} catch (UnsupportedEncodingException e) {
			}

			BigInteger totalOutput = fee;
			BigInteger totalInput = BigInteger.ZERO;

			try {
				for (int i = 0; i < destinations.size(); i++) {
					destination = destinations.get(i);
					btcAmount = btcAmounts.get(i);
					if (!destination.equals("") && btcAmount.compareTo(BigInteger.ZERO)>0) {
						totalOutput = totalOutput.add(btcAmount);
						tx.addOutput(btcAmount, new Address(params, destination));
					}
				}
			} catch (AddressFormatException e) {
				throw new Exception("The address is not valid.");
			}

			for (int i = 0; i < dataArrayList.size(); i+=32) {
				List<Byte> chunk = new ArrayList<Byte>(dataArrayList.subList(i, Math.min(i+32, dataArrayList.size())));
				chunk.add(0, (byte) chunk.size());
				while (chunk.size()<32+1) {
					chunk.add((byte) 0);
				}
				List<ECKey> keys = new ArrayList<ECKey>();
				for (ECKey key : wallet.getKeys()) {
					try {
						if (key.toAddress(params).equals(new Address(params, source))) {
							keys.add(key);
							break;
						}
					} catch (AddressFormatException e) {
					}
				}
				keys.add(new ECKey(null, Util.toByteArray(chunk)));
				Script script = ScriptBuilder.createMultiSigOutputScript(1, keys);
				tx.addOutput(BigInteger.valueOf(Config.dustSize), script);
				totalOutput = totalOutput.add(BigInteger.valueOf(Config.dustSize));
			}

			List<UnspentOutput> unspents = Util.getUnspents(source);
			List<Script> inputScripts = new ArrayList<Script>();			
			List<ECKey> inputKeys = new ArrayList<ECKey>();			

			Boolean atLeastOneRegularInput = false;
			for (UnspentOutput unspent : unspents) {
				String txHash = unspent.txid;
				byte[] scriptBytes = Hex.decode(unspent.scriptPubKey.hex.getBytes(Charset.forName("UTF-8")));
				Script script = new Script(scriptBytes);
				//if it's sent to an address and we don't yet have enough inputs or we don't yet have at least one regular input, or if it's sent to a multisig
				//in other words, we sweep up any unused multisig inputs with every transaction

				try {
					if ((useUnspentTxHash.equals(unspent.txid) && useUnspentVout.equals(unspent.vout)) || (useUnspentVout<0 && ((script.isSentToAddress() && (totalOutput.compareTo(totalInput)>0 || !atLeastOneRegularInput)) || (script.isSentToMultiSig())))) {
						//if we have this transaction in our wallet already, we shall confirm that it is not already spent
						if (wallet.getTransaction(new Sha256Hash(txHash))==null || wallet.getTransaction(new Sha256Hash(txHash)).getOutput(unspent.vout).isAvailableForSpending()) {
							if (script.isSentToAddress()) {
								atLeastOneRegularInput = true;
							}
							Sha256Hash sha256Hash = new Sha256Hash(txHash);	
							TransactionOutPoint txOutPt = new TransactionOutPoint(params, unspent.vout, sha256Hash);
							for (ECKey key : wallet.getKeys()) {
								try {
									if (key.toAddress(params).equals(new Address(params, source))) {
										//System.out.println("Spending "+sha256Hash+" "+unspent.vout);
										totalInput = totalInput.add(BigDecimal.valueOf(unspent.amount*Config.unit).toBigInteger());
										TransactionInput input = new TransactionInput(params, tx, new byte[]{}, txOutPt);
										tx.addInput(input);
										inputScripts.add(script);
										inputKeys.add(key);
										break;
									}
								} catch (AddressFormatException e) {
								}
							}
						}
					}
				} catch (Exception e) {
					logger.error("Error during transaction creation: "+e.toString());
					e.printStackTrace();
				}
			}

			if (!atLeastOneRegularInput) {
				throw new Exception("Not enough standard unspent outputs to cover transaction.");
			}

			if (totalInput.compareTo(totalOutput)<0) {
				logger.info("Not enough inputs. Output: "+totalOutput.toString()+", input: "+totalInput.toString());
				throw new Exception("Not enough BTC to cover transaction of "+String.format("%.8f",totalOutput.doubleValue()/Config.unit)+" BTC.");
			}
			BigInteger totalChange = totalInput.subtract(totalOutput);

			try {
				if (totalChange.compareTo(BigInteger.ZERO)>0) {
					tx.addOutput(totalChange, new Address(params, source));
				}
			} catch (AddressFormatException e) {
			}

			//sign inputs
			for (int i = 0; i<tx.getInputs().size(); i++) {
				Script script = inputScripts.get(i);
				ECKey key = inputKeys.get(i);
				TransactionInput input = tx.getInput(i);
				TransactionSignature txSig = tx.calculateSignature(i, key, script, SigHash.ALL, false);
				if (script.isSentToAddress()) {
					input.setScriptSig(ScriptBuilder.createInputScript(txSig, key));
				} else if (script.isSentToMultiSig()) {
					//input.setScriptSig(ScriptBuilder.createMultiSigInputScript(txSig));
					ScriptBuilder builder = new ScriptBuilder();
					builder.smallNum(0);
					builder.data(txSig.encodeToBitcoin());
					input.setScriptSig(builder.build());
				}
			}
		}
		tx.verify();
		return tx;
	}

	public Boolean sendTransaction(String source, Transaction tx) throws Exception {
		return sendTransaction(source, tx, false);
	}
	public Boolean sendTransaction(String source, Transaction tx, Boolean force) throws Exception {
		if (!Config.readOnly || force) {
			try {
				//System.out.println(tx);

				byte[] rawTxBytes = tx.bitcoinSerialize();
				String rawTx = new BigInteger(1, rawTxBytes).toString(16);
				rawTx = "0" + rawTx;
				//System.out.println(rawTx);
				//System.exit(0);

				Blocks blocks = Blocks.getInstance();
				//blocks.wallet.commitTx(txBet);
				if (Util.pushTx(rawTx)) {

				} else {
					throw new Exception("The transaction did not go through successfully. Please try again.");	
				}
				logger.info("Importing transaction (assigning block number -1)");
				blocks.importTransaction(tx, null, null);
				return true;
			} catch (Exception e) {
				throw new Exception(e.getMessage());
			}		
		} else {
			throw new Exception("Chancecoin is in read only mode.");			
		}
	}
}