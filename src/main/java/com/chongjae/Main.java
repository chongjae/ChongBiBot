package com.chongjae;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import org.sqlite.SQLiteConfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.SendMessage;
import com.webcerebrium.binance.api.BinanceApi;
import com.webcerebrium.binance.api.BinanceApiException;
import com.webcerebrium.binance.api.BinanceRequest;
import com.webcerebrium.binance.datatype.BinanceCandlestick;
import com.webcerebrium.binance.datatype.BinanceInterval;
import com.webcerebrium.binance.datatype.BinanceOrder;
import com.webcerebrium.binance.datatype.BinanceOrderPlacement;
import com.webcerebrium.binance.datatype.BinanceOrderSide;
import com.webcerebrium.binance.datatype.BinanceOrderType;
import com.webcerebrium.binance.datatype.BinanceSymbol;
import com.webcerebrium.binance.datatype.BinanceWalletAsset;

public class Main {

	public static final int NEW_INSERT = 0;
	public static final int INSERT_LAST = 1;
	public static final int UPDATE_LAST = 2;

	public static int minuteThreshold = 5 * 60;
	public static double rapidThreshold = 1.02;
	public static double sellThreshold = 1.03;
	public static double constantLossThreshold = 1.07;
	public static double constantProfitThreshold = 1.04;
	public static int sellCount = 2;
	public static int buyCnt = 2;
	public static int boughtCnt = 0;
	public static int maxBuyCnt = 3;
	public static int numberOfKindle = 3;
	public static boolean isReallyBuy;
	public static double totalBalance;
	public static double assetRate = 0.3;
	public static TelegramBot bot = new TelegramBot("457391264:AAF9CWBt9E2KG_neDp8N33kTXB5rkoeCZcM");
	public static Logger logger = Logger.getLogger("ChongCoinBot");
	public static double curProfit = 0;
	public static HashMap<String, CoinInfo> coins;

	public static void main(String[] args) {
		coins = new HashMap<String, CoinInfo>();
		FileHandler fh;
		isReallyBuy = "on".equals(args[0]);
		boolean needToupdateTotalBalance = true;
		try {
			fh = new FileHandler("./BinanceBot.log");
			logger.addHandler(fh);
			SimpleFormatter formatter = new SimpleFormatter();
			fh.setFormatter(formatter);
		} catch (Exception e) {
		}
		logger.info(String.valueOf(isReallyBuy));

		initCoinInfo();
		loadCoinInfo();
		loadUserInfo();

		while (true) {
			try {
				loadUserInfo();
				BinanceApi api = new BinanceApi();
				if (isReallyBuy && needToupdateTotalBalance) {
					boolean existOpenOrders = false;
					Map<String, BinanceWalletAsset> balance = api.balancesMap();
					Set<String> keys = balance.keySet();
					for (String key : keys) {
						try {
							if (api.openOrders(BinanceSymbol.valueOf(key + "ETH")).size() != 0) {
								existOpenOrders = true;
							}
						} catch (BinanceApiException e) {

						}
					}
					if (!existOpenOrders) {
						totalBalance = balance.get("ETH").free.doubleValue();
						sendMsgToTelegram("Current Balance : " + totalBalance, true);
						needToupdateTotalBalance = false;
					}
				}

				JsonArray prices = api.allBookTickers();
				for (int i = 0; i < prices.size(); i++) {
					JsonObject price = prices.get(i).getAsJsonObject();
					String coinName = price.get("symbol").toString().replaceAll("\"", "");
					if (coinName.equals("123456") || !coinName.endsWith("ETH")) {
						continue;
					}
				}
				for (int i = 0; i < prices.size(); i++) {
					JsonObject price = prices.get(i).getAsJsonObject();
					String key = price.get("symbol").toString().replaceAll("\"", "");
					if (!key.endsWith("ETH")) {
						continue;
					}
					if (coins.containsKey(key)) {
						double curSellPrice = Double.parseDouble(price.get("askPrice").toString().replaceAll("\"", ""));
						double curBuyPrice = Double.parseDouble(price.get("bidPrice").toString().replaceAll("\"", ""));
						CoinInfo coinInfo = coins.get(key);
						if (coinInfo.list.isEmpty()) {
							calMCAD(coinInfo);
							continue;
						}

						calMCAD(coinInfo);
						coinInfo.curPrice = curBuyPrice;

						if (coinInfo.buyPrice == 0 && coinInfo.isSignalForBuy()) {
							coinInfo.buyPrice = curSellPrice;
							sendMsgToTelegram(key + "이 급등하였습니다. Buy : " + curSellPrice, false);
							if (isReallyBuy && !coinInfo.isBought && boughtCnt < maxBuyCnt) {
								buyCoin(coinInfo);
							}
							continue;
						}

						if (coinInfo.buyPrice != 0) {
							double cutRate = coinInfo.buyPrice / curBuyPrice;

							if (cutRate >= constantLossThreshold || curBuyPrice / coinInfo.buyPrice > constantProfitThreshold) {
								cutRate = curBuyPrice / coinInfo.buyPrice;
								sendMsgToTelegram(key + "을 " + coinInfo.buyPrice + "에 매수하여, " + curBuyPrice
										+ "에 매도하였습니다. (" + cutRate + ") ", false);
								coinInfo.cutPrice = curBuyPrice;
								if (isReallyBuy && coinInfo.isBought) {
									sellCoin(coinInfo);
									if (boughtCnt == 0) {
										needToupdateTotalBalance = true;
									}
									curProfit += (100 * cutRate) - 100;
									sendMsgToTelegram("Cur Profit : " + curProfit + ", Bought : \n" + getBoughtList(),
											false);
								} else {
									coinInfo.buyPrice = 0;
								}
							} else if (coinInfo.isSignalForSell()) {
								double curRate = curBuyPrice / coinInfo.buyPrice;
								if (curRate > 1.02f || coinInfo.list.get(coinInfo.list.size() -1).rsi > 75) {
									sendMsgToTelegram(key + "을 " + coinInfo.buyPrice + "에 매수하여, " + curBuyPrice
											+ "에 매도하였습니다. (" + curRate + ") ", false);
									coinInfo.cutPrice = curBuyPrice;
									if (isReallyBuy && coinInfo.isBought) {
										sellCoin(coinInfo);
										if (boughtCnt == 0) {
											needToupdateTotalBalance = true;
										}
										curProfit += (100 * curRate) - 100;
										sendMsgToTelegram("Cur Profit : " + curProfit + ", Bought : \n" + getBoughtList(),
												false);
									} else {
										coinInfo.buyPrice = 0;
									}
								}
							}

							saveBoughtInfo(coinInfo);
						}
					}
				}

				try {
					Thread.sleep(1000);
				} catch (Exception e) {
				}
				logger.info("System is running....");
			} catch (BinanceApiException e) {
				logger.info("Binance API Exception is occurred : " + e.getMessage());
			}
		}
	}

	public static String getBoughtList() {
		StringBuffer ret = new StringBuffer();
		for (String key : coins.keySet()) {
			CoinInfo coin = coins.get(key);
			if (coin.buyPrice != 0) {
				ret.append(key + " : " + coin.buyPrice + "\n");
			}
		}
		return ret.toString();
	}

	public static void sendMsgToTelegram(String msg, boolean toMe) {

		logger.info("Send msg to Telegram : " + msg);
		Connection con = null;
		Statement stat = null;
		try {
			SQLiteConfig config = new SQLiteConfig();
			config.setReadOnly(true);
			Class.forName("org.sqlite.JDBC");
			con = DriverManager.getConnection("jdbc:sqlite:user.db", config.toProperties());
			stat = con.createStatement();
		} catch (Exception e) {
			e.printStackTrace();
		}
		try {
			ResultSet ret = stat.executeQuery("select * from user");
			boolean isDisableBuy = false;
			while (ret.next()) {
				String userId = ret.getString("user");

				if (toMe) {
					if (!"196764827".equals(userId)) {
						continue;
					}
				}

				if ("9999".equals(userId)) {
					isDisableBuy = true;
				}
				SendMessage request = new SendMessage(userId, msg).parseMode(ParseMode.HTML);
				bot.execute(request);
			}
			isReallyBuy = !isDisableBuy;
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	public static void buyCoin(CoinInfo coin) {
		try {
			BinanceApi api = new BinanceApi();
			BinanceSymbol symbol = new BinanceSymbol(coin.key);
			BinanceOrderPlacement placement = new BinanceOrderPlacement(symbol, BinanceOrderSide.BUY);
			placement.setType(BinanceOrderType.MARKET);
			double buyPrice = Double.valueOf(coin.priceStep.format(coin.buyPrice * 1.02));
			double quantity = Double.valueOf(coin.quantityStep.format(totalBalance * assetRate / buyPrice));

			if (buyPrice < coin.minPrice || quantity < coin.minQty) {
				sendMsgToTelegram("Order Fail. Price : " + buyPrice + ", Quantity : " + quantity, true);
				return;
			}
			placement.setPrice(BigDecimal.valueOf(buyPrice));
			placement.setQuantity(BigDecimal.valueOf(quantity));
			BinanceOrder order = api.getOrderById(symbol, api.createOrder(placement).get("orderId").getAsLong());
			sendMsgToTelegram(order.toString(), true);
			if (order.toString().contains("ERROR") || order.toString().contains("Fail")) {
				coin.buyPrice = 0;
			} else {
				boughtCnt++;
				coin.isBought = true;
				coin.buyDate = System.currentTimeMillis();
				saveBoughtInfo(coin);
			}
		} catch (BinanceApiException e) {
			sendMsgToTelegram(e.getMessage(), true);
		}
	}

	public static void sellCoin(CoinInfo coin) throws BinanceApiException {
		try {
			BinanceApi api = new BinanceApi();
			BinanceSymbol symbol = new BinanceSymbol(coin.key);
			BinanceOrderPlacement placement = new BinanceOrderPlacement(symbol, BinanceOrderSide.SELL);
			placement.setType(BinanceOrderType.MARKET);
			double sellPrice = Double.valueOf(coin.priceStep.format(coin.cutPrice * 0.98));
			double quantity = Double.valueOf(coin.quantityStep
					.format(new BinanceApi().balancesMap().get(coin.key.substring(0, coin.key.indexOf("ETH"))).free
							.doubleValue()));

			if (sellPrice < coin.minPrice || quantity < coin.minQty) {
				sendMsgToTelegram("Order Fail. Price : " + sellPrice + ", Quantity : " + quantity, true);
				return;
			}

			placement.setPrice(BigDecimal.valueOf(sellPrice));
			placement.setQuantity(BigDecimal.valueOf(quantity));
			BinanceOrder order = api.getOrderById(symbol, api.createOrder(placement).get("orderId").getAsLong());
			sendMsgToTelegram(order.toString(), true);
			if (order.toString().contains("ERROR") || order.toString().contains("Fail")) {
				List<BinanceOrder> orders = api.openOrders(BinanceSymbol.valueOf(coin.key));
				if (orders.size() != 0) {
					for (BinanceOrder existOrder : orders) {
						api.deleteOrder(existOrder);
					}
				}
			} else {
				boughtCnt--;
				coin.buyPrice = 0;
				coin.isBought = false;
			}
		} catch (BinanceApiException e) {
			sendMsgToTelegram(e.getMessage(), true);
		}
	}

	public static void initCoinInfo() {
		try {
			JsonObject obj = new BinanceRequest("https://www.binance.com/api/v1/exchangeInfo").read().asJsonObject();
			JsonArray array = obj.getAsJsonArray("symbols");
			for (int i = 0; i < array.size(); i++) {
				JsonObject info = array.get(i).getAsJsonObject();
				String coinName = info.get("symbol").toString().replaceAll("\"", "");
				if (coinName.equals("123456") || !coinName.endsWith("ETH")) {
					continue;
				}
				JsonArray filters = info.getAsJsonArray("filters");
				String minQty = "";
				String stepSize = "";
				String minPrice = "";
				String stepPrice = "";
				for (int k = 0; k < filters.size(); k++) {
					if (filters.get(k).getAsJsonObject().get("filterType").toString().replaceAll("\"", "")
							.equals("LOT_SIZE")) {
						minQty = filters.get(k).getAsJsonObject().get("minQty").toString().replaceAll("\"", "");
						stepSize = filters.get(k).getAsJsonObject().get("stepSize").toString().replaceAll("\"", "");
					}
					if (filters.get(k).getAsJsonObject().get("filterType").toString().replaceAll("\"", "")
							.equals("PRICE_FILTER")) {
						minPrice = filters.get(k).getAsJsonObject().get("minPrice").toString().replaceAll("\"", "");
						stepPrice = filters.get(k).getAsJsonObject().get("tickSize").toString().replaceAll("\"", "");
					}

				}
				String[] format = stepSize.split("\\.");
				String formatForm = "";
				for (int k = 0; k < format[0].length(); k++) {
					formatForm += "#";
				}
				formatForm += ".";
				for (int k = 0; k < format[1].indexOf("1") + 1; k++) {
					formatForm += "#";
				}
				if (formatForm.charAt(formatForm.length() - 1) == '.') {
					formatForm = formatForm.substring(0, formatForm.length() - 1);
				}

				String[] formatForPrice = stepPrice.split("\\.");
				String formatFormForPrice = "";
				for (int k = 0; k < formatForPrice[0].length(); k++) {
					formatFormForPrice += "#";
				}
				formatFormForPrice += ".";
				for (int k = 0; k < formatForPrice[1].indexOf("1") + 1; k++) {
					formatFormForPrice += "#";
				}
				if (formatFormForPrice.charAt(formatFormForPrice.length() - 1) == '.') {
					formatFormForPrice = formatFormForPrice.substring(0, formatFormForPrice.length() - 1);
				}

				NumberFormat formatter = new DecimalFormat(formatForm);
				formatter.setRoundingMode(RoundingMode.DOWN);

				NumberFormat formatterForPrice = new DecimalFormat(formatFormForPrice);
				formatterForPrice.setRoundingMode(RoundingMode.DOWN);

				coins.put(coinName, new CoinInfo(coinName, Double.valueOf(minQty), formatter, Double.valueOf(minPrice),
						formatterForPrice));
			}
		} catch (BinanceApiException e) {
			e.printStackTrace();
		}
	}

	public static void calMCAD(CoinInfo coin) {
		try {
			int firstEMA = 12;
			int secondEMA = 26;
			int signalDays = 9;
			int nDays = 474;

			BinanceApi api = new BinanceApi();
			BinanceSymbol symbol = new BinanceSymbol(coin.key);
			List<BinanceCandlestick> klines = api.klines(symbol, BinanceInterval.FIFTEEN_MIN, secondEMA + nDays, null);
			if (klines.size() < secondEMA + nDays) {
				return;
			}
			ArrayList<MACDInfo> list = coin.list;
			if (!list.isEmpty()) {
				BinanceCandlestick candle = klines.get(klines.size() - 1);
				MACDInfo macdInfo = list.get(list.size() - 1);
				int update = INSERT_LAST;
				if (candle.closeTime - macdInfo.date == 0) {
					macdInfo.closePrice = candle.close.doubleValue();
					update = UPDATE_LAST;
				} else {
					list.add(new MACDInfo(candle.closeTime, candle.close.doubleValue()));
					macdInfo = list.get(list.size() - 1);
				}
				MACDInfo prevMacdInfo = list.get(list.size() - 2);
				macdInfo.ema12 = macdInfo.closePrice * (2.0 / ((double) firstEMA + 1))
						+ prevMacdInfo.ema12 * (1.0 - (2.0 / ((double) firstEMA + 1)));
				macdInfo.ema26 = macdInfo.closePrice * (2.0 / ((double) secondEMA + 1))
						+ prevMacdInfo.ema26 * (1.0 - (2.0 / ((double) secondEMA + 1)));
				macdInfo.macd = macdInfo.ema12 - macdInfo.ema26;
				macdInfo.signal = macdInfo.macd * (2.0 / ((double) signalDays + 1.0))
						+ prevMacdInfo.signal * (1.0 - (2.0 / ((double) signalDays + 1.0)));
				calRSI(list, false);
				saveCoinInfoToDB(coin, update);
				return;
			}

			for (BinanceCandlestick candle : klines) {
				list.add(new MACDInfo(candle.closeTime, candle.close.doubleValue()));
			}

			// Step1. Cal first 12 ema
			double avg = 0;
			for (int i = 0; i < firstEMA; i++) {
				MACDInfo macdInfo = list.get(i);
				avg += macdInfo.closePrice;
			}

			// Step2. Cal all of 12 ema
			list.get(firstEMA - 1).ema12 = (double) avg / (double) firstEMA;
			for (int i = firstEMA; i < list.size(); i++) {
				MACDInfo macdInfo = list.get(i);
				MACDInfo prevMacdInfo = list.get(i - 1);
				if (i < secondEMA) {
					avg += macdInfo.closePrice;
				}
				macdInfo.ema12 = macdInfo.closePrice * (2.0 / ((double) firstEMA + 1))
						+ prevMacdInfo.ema12 * (1.0 - (2.0 / ((double) firstEMA + 1)));
			}
			list.get(secondEMA - 1).ema26 = (double) avg / (double) secondEMA;

			// Step3. Cal all of 26 ema
			for (int i = secondEMA; i < list.size(); i++) {
				MACDInfo macdInfo = list.get(i);
				MACDInfo prevMacdInfo = list.get(i - 1);
				macdInfo.ema26 = macdInfo.closePrice * (2.0 / ((double) secondEMA + 1))
						+ prevMacdInfo.ema26 * (1.0 - (2.0 / ((double) secondEMA + 1)));

			}

			// Step4. Cal all of macd
			for (int i = secondEMA - 1; i < list.size(); i++) {
				MACDInfo macdInfo = list.get(i);
				macdInfo.macd = macdInfo.ema12 - macdInfo.ema26;
			}

			avg = 0;
			for (int i = secondEMA - 1; i < secondEMA + signalDays - 1; i++) {
				MACDInfo macdInfo = list.get(i);
				avg += macdInfo.macd;
			}
			avg /= (double) signalDays;

			list.get(secondEMA + signalDays).signal = avg;

			// Step5. Cal all of signal
			for (int i = secondEMA + signalDays + 1; i < list.size(); i++) {
				MACDInfo macdInfo = list.get(i);
				MACDInfo prevMacdInfo = list.get(i - 1);
				macdInfo.signal = macdInfo.macd * (2.0 / ((double) signalDays + 1.0))
						+ prevMacdInfo.signal * (1.0 - (2.0 / ((double) signalDays + 1.0)));
			}

			/*
			 * macd > signal => Buy macd < signal => Sell
			 */

			calRSI(list, true);
			saveCoinInfoToDB(coin, NEW_INSERT);
		} catch (BinanceApiException e) {
			// TODO: handle exception
		}
	}

	public static void calRSI(ArrayList<MACDInfo> list, boolean isFirst) {
		int nDays = 14;

		if (!isFirst) {
			MACDInfo info = list.get(list.size() - 1);
			MACDInfo preInfo = list.get(list.size() - 2);
			info.diff = info.closePrice - preInfo.closePrice;
			double day = (double) nDays;
			double day2 = day - 1.0;
			if (info.diff > 0) {
				info.avgUp = ((preInfo.avgUp * day2) + info.diff) / day;
				info.avgDown = (preInfo.avgDown * day2) / day;
			} else {
				info.avgUp = (preInfo.avgUp * day2) / day;
				info.avgDown = ((preInfo.avgDown * day2) - info.diff) / day;
			}
			info.rs = info.avgUp / info.avgDown;
			info.rsi = 100.0 - (100.0 / (1.0 + info.rs));
			return;
		}
		// Step1. Cal Diff
		for (int i = 1; i < list.size(); i++) {
			MACDInfo info = list.get(i);
			MACDInfo preInfo = list.get(i - 1);
			info.diff = info.closePrice - preInfo.closePrice;
		}

		// Step2. Cal avg of up and down
		MACDInfo firstAvg = list.get(nDays);
		for (int i = 1; i < nDays + 1; i++) {
			MACDInfo info = list.get(i);
			if (info.diff > 0) {
				firstAvg.avgUp += info.diff;
			} else {
				firstAvg.avgDown -= info.diff;
			}
		}

		firstAvg.avgUp /= (double) nDays;
		firstAvg.avgDown /= (double) nDays;

		for (int i = nDays + 1; i < list.size(); i++) {
			MACDInfo info = list.get(i);
			MACDInfo preInfo = list.get(i - 1);

			double day = (double) nDays;
			double day2 = day - 1.0;
			if (info.diff > 0) {
				info.avgUp = ((preInfo.avgUp * day2) + info.diff) / day;
				info.avgDown = (preInfo.avgDown * day2) / day;
			} else {
				info.avgUp = (preInfo.avgUp * day2) / day;
				info.avgDown = ((preInfo.avgDown * day2) - info.diff) / day;
			}

			info.rs = info.avgUp / info.avgDown;

			info.rsi = 100.0 - (100.0 / (1.0 + info.rs));
		}
	}

	public static void saveCoinInfoToDB(CoinInfo coin, int sqlSelector) {
		Connection con = null;
		try {
			Class.forName("com.mysql.cj.jdbc.Driver");
			con = DriverManager.getConnection("jdbc:mysql://localhost:3306/coinInfo?serverTimezone=UTC" , "coin", "coin1234");
		} catch (Exception e) {
			e.printStackTrace();
		}
		try {
			String sql;
			PreparedStatement pstmt = null;
			switch (sqlSelector) {
			case NEW_INSERT:
				sql = "INSERT INTO coins(coinName,closePrice,macd,macdSignal,rsi,date) VALUES(?,?,?,?,?,?)";
				pstmt = con.prepareStatement(sql);

				for (MACDInfo info : coin.list) {
					pstmt.setString(1, coin.key);
					pstmt.setDouble(2, info.closePrice);
					pstmt.setDouble(3, info.macd);
					pstmt.setDouble(4, info.signal);
					pstmt.setDouble(5, info.rsi);
					pstmt.setDouble(6, info.date);
					pstmt.executeUpdate();
				}
				break;
			case INSERT_LAST:
				sql = "INSERT INTO coins(coinName,closePrice,macd,macdSignal,rsi,date) VALUES(?,?,?,?,?,?)";
				pstmt = con.prepareStatement(sql);

				MACDInfo info = coin.list.get(coin.list.size() - 1);
				pstmt.setString(1, coin.key);
				pstmt.setDouble(2, info.closePrice);
				pstmt.setDouble(3, info.macd);
				pstmt.setDouble(4, info.signal);
				pstmt.setDouble(5, info.rsi);
				pstmt.setDouble(6, info.date);
				pstmt.executeUpdate();
				break;
			case UPDATE_LAST:
				sql = "UPDATE coins SET closePrice = ?, macd = ?, macdSignal = ? , rsi = ? WHERE coinName = ? AND date = ?";
				pstmt = con.prepareStatement(sql);

				MACDInfo info2 = coin.list.get(coin.list.size() - 1);
				pstmt.setDouble(1, info2.closePrice);
				pstmt.setDouble(2, info2.macd);
				pstmt.setDouble(3, info2.signal);
				pstmt.setDouble(4, info2.rsi);
				pstmt.setString(5, coin.key);
				pstmt.setDouble(6, info2.date);
				pstmt.executeUpdate();
				break;
			default:
				break;
			}
		} catch (SQLException e) {
			logger.info(e.getMessage());
		} catch (Exception e) {
			logger.info(e.getMessage());
		}
	}
	
	public static void saveBoughtInfo(CoinInfo coin) {
		Connection con = null;
		try {
			Class.forName("com.mysql.cj.jdbc.Driver");
			con = DriverManager.getConnection("jdbc:mysql://localhost:3306/coinInfo?serverTimezone=UTC" , "coin", "coin1234");
		} catch (Exception e) {
			e.printStackTrace();
		}
		try {
			String sql;
			PreparedStatement pstmt = null;
			sql = "INSERT INTO boughtCoins(coinName,buyDate,buyPrice,curPrice,isBought) VALUES(?,?,?,?,?) on DUPLICATE KEY UPDATE buyDate=?, buyPrice=?, curPrice=?, isBought=?";
			pstmt = con.prepareStatement(sql);

			pstmt.setString(1, coin.key);
			pstmt.setDouble(2, coin.buyDate);
			pstmt.setDouble(3, coin.buyPrice);
			pstmt.setDouble(4, coin.curPrice);
			pstmt.setBoolean(5, coin.isBought);
			pstmt.setDouble(6, coin.buyDate);
			pstmt.setDouble(7, coin.buyPrice);
			pstmt.setDouble(8, coin.curPrice);
			pstmt.setBoolean(9, coin.isBought);
			pstmt.executeUpdate();
			pstmt.close();
		} catch (SQLException e) {
			logger.info(e.getMessage());
		} catch (Exception e) {
			logger.info(e.getMessage());
		}
	}
	
	public static void loadCoinInfo() {
		Connection con = null;
		try {
			Class.forName("com.mysql.cj.jdbc.Driver");
			con = DriverManager.getConnection("jdbc:mysql://localhost:3306/coinInfo?serverTimezone=UTC" , "coin", "coin1234");
			String sql = "select * from boughtCoins";
			Statement stat = con.createStatement();
			ResultSet rs = stat.executeQuery(sql);
			while (rs.next()) {
				String key = rs.getString("coinName");
				double buyDate = rs.getDouble("buyDate");
				double buyPrice = rs.getDouble("buyPrice");
				double curPrice = rs.getDouble("curPrice");
				boolean isBought = rs.getBoolean("isBought");
				CoinInfo coin = coins.get(key);
				if(coin != null) {
					coin.buyDate = buyDate;
					coin.buyPrice = buyPrice;
					coin.curPrice = curPrice;
					coin.isBought = isBought;
					if (isBought) {
							boughtCnt++;
					}
				}
			}
			rs.close();
			stat.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static void loadUserInfo() {
		Connection con = null;
		try {
			Class.forName("com.mysql.cj.jdbc.Driver");
			con = DriverManager.getConnection("jdbc:mysql://localhost:3306/coinInfo?serverTimezone=UTC" , "coin", "coin1234");
			String sql = "select * from userInfo";
			Statement stat = con.createStatement();
			ResultSet rs = stat.executeQuery(sql);
			while (rs.next()) {
				constantLossThreshold = rs.getDouble("lossRate");
				constantProfitThreshold = rs.getDouble("profitRate");
				assetRate = rs.getDouble("buyRate");
				maxBuyCnt = (int) (1.0 / assetRate);
			}
			rs.close();
			stat.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static class MACDInfo {
		long date;
		double closePrice;
		double ema12;
		double ema26;
		double macd;
		double signal;
		double diff;
		double avgUp;
		double avgDown;
		double rs;
		double rsi;

		public MACDInfo(long date, double closePrice) {
			this.date = date;
			this.closePrice = closePrice;
		}
	}

	public static class CoinInfo {
		public String key;
		public double buyPrice;
		public double curPrice;
		public double cutPrice;
		public double buyDate;
		public double minQty;
		public NumberFormat quantityStep;
		public double minPrice;
		public NumberFormat priceStep;
		public boolean isBought;
		public ArrayList<MACDInfo> list = new ArrayList<MACDInfo>();

		public CoinInfo(String key, double minQty, NumberFormat step, double minPrice, NumberFormat priceStep) {
			this.key = key;
			this.minQty = minQty;
			this.quantityStep = step;
			this.minPrice = minPrice;
			this.priceStep = priceStep;
		}

		public boolean isSignalForBuy() {
			if (list.isEmpty()) {
				return false;
			}
			MACDInfo isSignal = null;
			for (int i = list.size() - 1; i > 0; i--) {
				MACDInfo info = list.get(i);
				if (info.macd > info.signal) {
					if (info.rsi < 35) {
						isSignal = info;
					}
				} else {
					break;
				}
			}
			if (isSignal == null) {
				return false;
			} else {
				long diffSeconds = (System.currentTimeMillis() - isSignal.date) / 1000;
				
				if(diffSeconds < 60 * 15) {
					return true;
				} else {
					return false;
				}

			}

		}

		public boolean isSignalForSell() {
			if (list.isEmpty()) {
				return false;
			}
			MACDInfo info = list.get(list.size() - 1);
			if (info.macd < info.signal) {
				return true;
			}
			return false;
		}
	}

}
