package com.manumusicprod.playcam.core;

import java.util.List;

/**
 * Liaison Wi-Fi par le nom, sans adresse IP : le téléphone voit l'ordinateur dans sa liste, le choisit,
 * puis se déconnecte. (python3 companion/mmp_connect.py --console --tone --no-usb --seconds 12)
 */
public class PairTest {
    public static void main(String[] args) throws Exception {
        final String[] sender = {null};
        ConnectReceiver r = new ConnectReceiver("Téléphone de test", new ConnectReceiver.Listener() {
            public void onMidi(byte[] d, int o, int l, long t) {}
            public void onSender(String name, String h) { sender[0] = name; }
            public void onLevel(float pl, float pr, float rms) {}
        });
        r.start();
        int fails = 0;
        long until = System.currentTimeMillis() + 8000;
        List<ConnectReceiver.Computer> list = r.computers();
        while (list.isEmpty() && System.currentTimeMillis() < until) { Thread.sleep(100); list = r.computers(); }
        boolean seen = !list.isEmpty();
        System.out.println((seen ? "OK   " : "FAIL ") + "Ordinateurs trouvés : " + (seen ? list.get(0).name : "aucun")); if (!seen) fails++;
        if (seen) {
            r.connectTo(list.get(0));
            until = System.currentTimeMillis() + 5000;
            while (!(r.senderActive() && r.audioActive()) && System.currentTimeMillis() < until) Thread.sleep(50);
            boolean linked = r.senderActive() && r.audioActive() && list.get(0).name.equals(r.senderName());
            System.out.println((linked ? "OK   " : "FAIL ") + "Relié par le nom : " + r.senderName()); if (!linked) fails++;
            r.disconnect();
            Thread.sleep(2500);
            boolean off = !r.senderActive() && !r.audioActive();
            System.out.println((off ? "OK   " : "FAIL ") + "Déconnexion : l'ordinateur n'envoie plus rien"); if (!off) fails++;
        }
        r.stop();
        System.out.println(fails == 0 ? "\nLIAISON PAR LE NOM : TOUS LES TESTS PASSENT" : "\n" + fails + " ÉCHEC(S)");
        System.exit(fails == 0 ? 0 : 1);
    }
}
