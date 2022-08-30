package org.tron.program;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.tron.api.WalletGrpc;
import org.tron.protos.Protocol.Transaction;

@Slf4j
public class SendTx {

  private ExecutorService broadcastExecutorService;
  private List<WalletGrpc.WalletBlockingStub> blockingStubFullList = new ArrayList<>();
  private int maxRows; //max read rows
  private int onceSendTxNum = 10000;

  public SendTx(String[] fullNodes, int broadcastThreadNum, int maxRows) {
    broadcastExecutorService = Executors.newFixedThreadPool(broadcastThreadNum);

    for (String fullNode : fullNodes) {
      //construct grpc stub
      ManagedChannel channelFull = ManagedChannelBuilder.forTarget(fullNode)
          .usePlaintext(true).build();
      WalletGrpc.WalletBlockingStub blockingStubFull = WalletGrpc.newBlockingStub(channelFull);
      blockingStubFullList.add(blockingStubFull);
      this.maxRows = maxRows;
    }
  }

  private void sendTx(List<Transaction> list) {
    Random random = new Random();
    List<Future<Boolean>> futureList = new ArrayList<>(list.size());
    list.forEach(transaction -> {
      futureList.add(broadcastExecutorService.submit(() -> {
        int index = random.nextInt(blockingStubFullList.size());
        blockingStubFullList.get(index).broadcastTransaction(transaction);
        return true;
      }));
    });
    futureList.forEach(ret -> {
      try {
        ret.get();
      } catch (InterruptedException | ExecutionException e) {
        e.printStackTrace();
      }
    });
  }

  private void readTxAndSend(String path) {
    File file = new File(path);
    logger.info("[Begin] send tx");
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(new FileInputStream(file)))) {
      String line = reader.readLine();
      List<Transaction> lineList = new ArrayList<>();
      int count = 0;
      while (line != null) {
        try {
          lineList.add(Transaction.parseFrom(Hex.decode(line)));
          count += 1;
          if (count > maxRows) {
            break;
          }
          if (count % onceSendTxNum == 0) {
            sendTx(lineList);
            lineList.clear();
            logger.info("Send tx num = " + count);
          }
        } catch (Exception e) {
        }
        line = reader.readLine();
      }
      if (!lineList.isEmpty()) {
        sendTx(lineList);
        lineList.clear();
        logger.info("Send total tx num = " + count);
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    logger.info("[Final] send tx end");
  }

  public static void main(String[] args) {
    //read the parameter
    String[] fullNodes = args[0].split(";");
    int broadcastThreadNum = Integer.parseInt(args[1]);
    String filePath = args[2];
    int maxRows = -1;
    if (args.length > 3) {
      maxRows = Integer.parseInt(args[3]);
    }
    if (maxRows < 0) {
      maxRows = Integer.MAX_VALUE;
    }
    SendTx sendTx = new SendTx(fullNodes, broadcastThreadNum, maxRows);
    //send tx
    sendTx.readTxAndSend(filePath);
    System.exit(0);
  }
}
