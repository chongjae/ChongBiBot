import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

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

public class main {

	public static int minuteThreshold = 5 * 60;
	public static double rapidThreshold = 1.02;
	public static double sellThreshold = 1.03;
	public static double constantSellThreshold = 1.1;
	public static int sellCount = 2;
	public static int buyCnt = 2;
	public static int boughtCnt = 0;
	public static int maxBuyCnt = 2;
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

		while (true) {
			try {
				BinanceApi api = new BinanceApi();
				if (needToupdateTotalBalance) {
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
						BinanceSymbol symbol = new BinanceSymbol(key);
						List<BinanceCandlestick> klines = api.klines(symbol, BinanceInterval.THREE_MIN, numberOfKindle,
								null);
						boolean isRapidUp = true;
						for (int k = 0; k < numberOfKindle; k++) {
							BinanceCandlestick binanceCandlestick = klines.get(k);
							double openPrice = binanceCandlestick.open.doubleValue();
							double closePrice = binanceCandlestick.close.doubleValue();
							double rate = closePrice / openPrice;
							if (rate < rapidThreshold) {
								isRapidUp = false;
							}
						}

						CoinInfo coinInfo = coins.get(key);

						if (isRapidUp && coinInfo.buyPrice == 0 && boughtCnt < maxBuyCnt) {
							coinInfo.buyPrice = curSellPrice;
							sendMsgToTelegram(key + "이 급등하였습니다. Buy : " + curSellPrice, false);
							if (isReallyBuy) {
								buyCoin(coinInfo);
							}
							continue;
						}

						if (coinInfo.buyPrice != 0) {
							klines = api.klines(symbol, BinanceInterval.FIFTEEN_MIN, 1, null);
							BinanceCandlestick binanceCandlestick = klines.get(0);
							double openPrice = binanceCandlestick.open.doubleValue();
							double closePrice = binanceCandlestick.close.doubleValue();
							double rate = openPrice / closePrice;
							double cutRate = coinInfo.buyPrice / curBuyPrice;

							if (cutRate >= constantSellThreshold) {
								cutRate = curBuyPrice / coinInfo.buyPrice;
								sendMsgToTelegram(key + "을 " + coinInfo.buyPrice + "에 매수하여, " + curBuyPrice
										+ "에 매도하였습니다. (" + cutRate + ") ", false);
								curProfit += (100 * cutRate) - 100;
								sendMsgToTelegram("Cur Profit : " + curProfit + ", Bought : \n" + getBoughtList(),
										false);
								coinInfo.cutPrice = curBuyPrice;
								if (isReallyBuy) {
									sellCoin(coinInfo);
								}
								if (boughtCnt == 0) {
									needToupdateTotalBalance = true;
								}
							} else if (rate > sellThreshold) {
								double curRate = curBuyPrice / coinInfo.buyPrice;
								if (curRate > 1.03f) {
									sendMsgToTelegram(key + "을 " + coinInfo.buyPrice + "에 매수하여, " + curBuyPrice
											+ "에 매도하였습니다. (" + curRate + ") ", false);
									curProfit += (100 * curRate) - 100;
									sendMsgToTelegram("Cur Profit : " + curProfit + ", Bought : \n" + getBoughtList(),
											false);
									coinInfo.cutPrice = curBuyPrice;
									if (isReallyBuy) {
										sellCoin(coinInfo);
									}
									if (boughtCnt == 0) {
										needToupdateTotalBalance = true;
									}
								}
							}
						}
					}
				}

				Thread.sleep(1000);
				logger.info("System is running....");
			} catch (Exception e) {
				logger.info(e.getMessage());
			} catch (BinanceApiException e) {
				logger.info(e.getMessage());
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
			Class.forName("org.sqlite.JDBC");
			con = DriverManager.getConnection("jdbc:sqlite:user.db");
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
			placement.setType(BinanceOrderType.LIMIT);
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
				coin.buyPrice = order.price.doubleValue();
				boughtCnt++;
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
			placement.setType(BinanceOrderType.LIMIT);
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
				if(orders.size() != 0) {
					for(BinanceOrder existOrder : orders) {
						api.deleteOrder(existOrder);
					}
				}
			} else {
				boughtCnt--;
				coin.buyPrice = 0;
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
	public static void calMCAD() {
		try {
			int firstEMA = 12;
			int secondEMA = 26;
			int nDays = 30;

			BinanceApi api = new BinanceApi();
			BinanceSymbol symbol = new BinanceSymbol("TRXETH");
			List<BinanceCandlestick> klines = api.klines(symbol, BinanceInterval.FIFTEEN_MIN, secondEMA + nDays, null);
			if (klines.size() < secondEMA + nDays) {
				return;
			}
			ArrayList<MACDInfo> list = new ArrayList<>();
			for (BinanceCandlestick candle : klines) {
				list.add(new MACDInfo(candle.closeTime, candle.close.doubleValue()));
				System.out.println(candle.close);
			}
			double avg = 0;
			for (int i = 0; i < firstEMA; i++) {
				MACDInfo macdInfo = list.get(i);
				avg += macdInfo.closePrice;
			}

			System.out.println("=================" + avg);
			list.get(firstEMA - 1).ema12 = (double) avg / (double) firstEMA;
			System.out.println(avg / firstEMA);
			for (int i = firstEMA; i < list.size(); i++) {
				MACDInfo macdInfo = list.get(i);
				MACDInfo prevMacdInfo = list.get(i - 1);
				if (i < secondEMA) {
					avg += macdInfo.closePrice;
				}
				macdInfo.ema12 = macdInfo.closePrice * (2.0 / ((double) firstEMA + 1))
						+ prevMacdInfo.ema12 * (1.0 - (2.0 / ((double) firstEMA + 1)));
				System.out.println(prevMacdInfo.ema12);
			}
			list.get(secondEMA - 1).ema26 = (double) avg / (double) secondEMA;
			System.out.println("=================" + avg);

			for (int i = secondEMA; i < list.size(); i++) {
				MACDInfo macdInfo = list.get(i);
				MACDInfo prevMacdInfo = list.get(i - 1);
				macdInfo.ema26 = macdInfo.closePrice * (2.0 / ((double) secondEMA + 1))
						+ prevMacdInfo.ema26 * (1.0 - (2.0 / ((double) secondEMA + 1)));
				System.out.println(prevMacdInfo.ema26);

			}

			avg = 0;
			for (int i = secondEMA - 1; i < list.size(); i++) {
				MACDInfo macdInfo = list.get(i);
				System.out.println(macdInfo.ema12 + " - " + macdInfo.ema26);
				macdInfo.macd = macdInfo.ema12 - macdInfo.ema26;
				avg += macdInfo.macd;
			}

			avg /= nDays;

			list.get(list.size() - 1).signal = avg;

			for (MACDInfo info : list) {
				System.out.println(info.macd);
			}
		} catch (BinanceApiException e) {
			// TODO: handle exception
		}
	}

	public static class MACDInfo {
		long date;
		double closePrice;
		double ema12;
		double ema26;
		double macd;
		double signal;

		public MACDInfo(long date, double closePrice) {
			this.date = date;
			this.closePrice = closePrice;
		}
	}

	public static class CoinInfo {
		public String key;
		public double buyPrice;
		public double cutPrice;
		public double minQty;
		public NumberFormat quantityStep;
		public double minPrice;
		public NumberFormat priceStep;

		public CoinInfo(String key, double minQty, NumberFormat step, double minPrice, NumberFormat priceStep) {
			this.key = key;
			this.minQty = minQty;
			this.quantityStep = step;
			this.minPrice = minPrice;
			this.priceStep = priceStep;
		}
	}

}
