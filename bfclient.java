import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;

class Route {
	String dest;
	String link;
	double cost;
	int timeout;
	boolean staitcRoute = false;
}

class Neighbor {
	String ip;
	int port;
	double weight;
	boolean up = true;
	long lastSentTime;
	long lastReceivedTime;

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof Neighbor) {
			return ip.equals(((Neighbor) obj).ip)
					&& port == ((Neighbor) obj).port;
		}
		return false;
	}

	@Override
	public String toString() {
		return ip + ":" + port;
	}
}

public class bfclient extends TimerTask {
	// the client port
	int port;

	// time interval for send update route message to all neighbors
	int timeout; // in seconds

	// distance list
	Map<String, Neighbor> neighbors = new TreeMap<String, Neighbor>();
	Map<String, Route> routes = new TreeMap<String, Route>();
	Set<String> localIps = new HashSet<String>();

	boolean changed = false;

	public void getLocalIPs() {
		try {
			Enumeration<NetworkInterface> en = NetworkInterface
					.getNetworkInterfaces();
			while (en.hasMoreElements()) {
				NetworkInterface intf = (NetworkInterface) en.nextElement();
				Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses();
				while (enumIpAddr.hasMoreElements()) {
					InetAddress inetAddress = (InetAddress) enumIpAddr
							.nextElement();
					if (!inetAddress.isLoopbackAddress()
							&& !inetAddress.isLinkLocalAddress()
							&& inetAddress.isSiteLocalAddress()) {
						localIps.add(inetAddress.getHostAddress().toString());
					}
				}
			}
		} catch (SocketException e) {
		}
		// System.out.println(localIps);
	}

	boolean isLocalHost(String link) {
		String[] ss = link.split(":");
		return localIps.contains(ss[0]) && this.port == Integer.parseInt(ss[1]);
	}

	/**
	 * <pre>
	 * RouteUpdate
	 * port  
	 * cost 
	 * n  
	 * dest cost 
	 * dest cost 
	 * ...
	 * </pre>
	 * 
	 * @param neighbor
	 * @throws IOException
	 */
	void sendRouteUpdate(Neighbor neighbor) throws IOException {
		DatagramSocket socket = new DatagramSocket();
		String msg = String.format("RouteUpdate\n%d\n%f\n%d\n", port,
				neighbor.weight, routes.size());
		for (Route route : routes.values()) {
			msg += String.format("%s %f\n", route.link, route.cost);
		}
		byte[] buf = msg.getBytes();
		DatagramPacket p = new DatagramPacket(buf, buf.length,
				InetAddress.getByName(neighbor.ip), neighbor.port);
		socket.send(p);
		// System.out.println("send " + neighbor.port + " " + msg);
		socket.close();
		neighbor.lastSentTime = System.currentTimeMillis();
	}

	/**
	 * <pre>
	 * LinkDown
	 * port
	 * </pre>
	 * 
	 * @throws IOException
	 */
	void sendLinkDown(Neighbor neighbor) throws IOException {
		DatagramSocket socket = new DatagramSocket();
		String msg = String.format("LinkDown\n%d\n", port);
		byte[] buf = msg.getBytes();
		DatagramPacket p = new DatagramPacket(buf, buf.length,
				InetAddress.getByName(neighbor.ip), neighbor.port);
		socket.send(p);
		socket.close();
	}

	/**
	 * <pre>
	 * LinkUp
	 * port
	 * </pre>
	 * 
	 * @throws IOException
	 */
	void sendLinkUp(Neighbor neighbor) throws IOException {
		neighbor.lastReceivedTime = System.currentTimeMillis();
		DatagramSocket socket = new DatagramSocket();
		String msg = String.format("LinkUp\n%d\n", port);
		byte[] buf = msg.getBytes();
		DatagramPacket p = new DatagramPacket(buf, buf.length,
				InetAddress.getByName(neighbor.ip), neighbor.port);
		socket.send(p);
		socket.close();
	}

	void listenUDP() {
		UDPServer server = new UDPServer(port, this);
		server.start();
	}

	void commandLineInterface() {
		// LINKDOWN
		// LINKUP
		// SHOWRT
		// CLOSE
		Scanner scanner = new Scanner(System.in);
		while (true) {
			System.out.print(">");
			String line = scanner.nextLine();
			if (line == null) {
				break;
			}
			line = line.trim().toUpperCase();
			String[] ss = line.split("\\s+");
			if (ss.length < 1) {
				continue;
			}
			if (ss[0].equals("LINKDOWN")) {
				linkDown(ss[1], ss[2]);
			} else if (ss[0].equals("LINKUP")) {
				linkUp(ss[1], ss[2]);
			} else if (ss[0].equals("SHOWRT")) {
				printRoutes();
			} else if (ss[0].equals("CLOSE")) {
				System.exit(0);
			} else {
				System.out.println("Invalid Command");
			}
		}
		scanner.close();
	}

	void linkDown(String ip, String port) {
		String key = ip + ":" + port;
		Neighbor neighbor = neighbors.get(key);
		if (neighbor != null) {
			neighbor.up = false;
			try {
				// for(String)
				// update route
				for (String dest : routes.keySet().toArray(new String[0])) {
					Route r = routes.get(dest);
					if (r.link.endsWith(key)) {
						routes.remove(dest);
						if (neighbors.containsKey(dest)) {
							Neighbor n = neighbors.get(dest);
							if (n.up) {
								Route route = new Route();
								route.dest = dest;
								route.link = dest;
								route.cost = n.weight;
								updateRoute(route);
							}
						}
					}
				}
				sendLinkDown(neighbor);

			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	void linkUp(String ip, String port) {
		String key = ip + ":" + port;
		Neighbor neighbor = neighbors.get(key);
		if (neighbor != null) {
			neighbor.up = true;
			Route route = new Route();
			route.dest = key;
			route.link = key;
			route.cost = neighbor.weight;
			updateRoute(route);
			try {
				sendLinkUp(neighbor);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	void printRoutes() {
		System.out.printf("%s Distance vector list is:\n", new Date());
		for (Route route : routes.values()) {
			System.out.printf("Destination = %s, Cost = %.1f, Link = (%s)\n",
					route.dest, route.cost, route.link);
		}
	}

	void start() {
		getLocalIPs();
		Timer timer = new Timer();
		timer.scheduleAtFixedRate(this, 1000, 1000);
		listenUDP();
		commandLineInterface();
	}

	@Override
	public void run() {
		checkRouteTimeout();
		checkChanged();
		checkSendUpdateTimeout();
		checkCloseTimeout();
	}

	void checkRouteTimeout() {
		for (String dest : routes.keySet().toArray(new String[0])) {
			Route route = routes.get(dest);
			if (route.staitcRoute) {
				continue;
			}
			route.timeout--;
			if (route.timeout <= 0) {
				routes.remove(dest);
			}
		}
	}

	void checkChanged() {
		if (!changed) {
			return;
		}
		for (Neighbor neighbor : neighbors.values()) {
			if (!neighbor.up) {
				continue;
			}
			try {
				sendRouteUpdate(neighbor);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		changed = false;
	}

	void checkSendUpdateTimeout() {
		long now = System.currentTimeMillis();
		for (Neighbor neighbor : neighbors.values()) {
			if (!neighbor.up) {
				continue;
			}
			if (now - neighbor.lastSentTime > timeout * 1000L) {
				try {
					sendRouteUpdate(neighbor);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	void checkCloseTimeout() {
		long now = System.currentTimeMillis();
		for (Neighbor neighbor : neighbors.values()) {
			if (!neighbor.up) {
				continue;
			}
			if (now - neighbor.lastReceivedTime > timeout * 3 * 1000L) {
				// neighbor.up = false;
				linkDown(neighbor.ip, neighbor.port + "");
			}
		}
	}

	void addNeighbor(Neighbor neighbor) {
		String key = neighbor.ip + ":" + neighbor.port;
		neighbor.lastReceivedTime = System.currentTimeMillis();
		neighbors.put(key, neighbor);
		Route route = new Route();
		route.dest = key;
		route.link = key;
		route.cost = neighbor.weight;
		route.staitcRoute = true;
		updateRoute(route);
	}

	synchronized void updateRoute(Route route) {
		Route old = routes.get(route.dest);
		if (old != null) {
			if (old.cost < route.cost) {
				return;
			}
		}
		route.timeout = timeout * 3;
		routes.put(route.dest, route);
		changed = true;
	}

	public static void main(String[] args) {
		if (args.length < 5) {
			System.out
					.println("Usage:java bfclient localport timeout [ipaddress1 port1 weight1 ...] ");
			return;
		}
		bfclient client = new bfclient();
		client.port = Integer.parseInt(args[0]);
		client.timeout = Integer.parseInt(args[1]);
		int n = (args.length - 2) / 3;
		for (int i = 0; i < n; i++) {
			Neighbor neighbor = new Neighbor();
			neighbor.ip = args[2 + i * 3];
			neighbor.port = Integer.parseInt(args[2 + i * 3 + 1]);
			neighbor.weight = Double.parseDouble(args[2 + i * 3 + 2]);
			client.addNeighbor(neighbor);
		}
		client.start();
	}

}

class UDPServer extends Thread {
	int port;
	bfclient client;

	public UDPServer(int port, bfclient client) {
		this.port = port;
		this.client = client;
	}

	@Override
	public void run() {
		try {
			DatagramSocket socket = new DatagramSocket(port);
			byte[] buf = new byte[1024];
			DatagramPacket p = new DatagramPacket(buf, buf.length);
			while (true) {
				socket.receive(p);
				parseMessage(p);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	void parseMessage(DatagramPacket p) {
		String ip = p.getAddress().getHostAddress();
		String s = new String(p.getData(), 0, p.getLength());
		Scanner scanner = new Scanner(s);
		String line = scanner.nextLine();
		int remotePort = Integer.parseInt(scanner.nextLine());
		String key = ip + ":" + remotePort;
		//System.out.println(line + key);
		if (line.equals("RouteUpdate")) {
			double nCost = Double.parseDouble(scanner.nextLine());
			Neighbor neighbor = new Neighbor();
			neighbor.ip = ip;
			neighbor.port = remotePort;
			neighbor.weight = nCost;
			if (!client.neighbors.containsKey(key)) {
				client.addNeighbor(neighbor);
			} else {
				neighbor = client.neighbors.get(key);
				neighbor.lastReceivedTime = System.currentTimeMillis();
				neighbor.up = true;
				Route route = new Route();
				route.dest = key;
				route.link = key;
				route.cost = nCost;
				client.updateRoute(route);
			}
			int n = Integer.parseInt(scanner.nextLine());
			for (int i = 0; i < n; i++) {
				line = scanner.nextLine();
				String[] ss = line.split(" ");
				String dest = ss[0];
				if (client.isLocalHost(dest)) {
					continue;
				}
				double cost = Double.parseDouble(ss[1]);
				Route route = new Route();
				route.dest = dest;
				route.link = key;
				route.cost = cost + nCost;
				client.updateRoute(route);
				//
				// if (route == null || nCost + cost < route.cost) {
				// client.routes.put(dest, route);
				// client.changed = true;
				// }
			}
		} else if (line.equals("LinkDown")) {
			Neighbor neighbor = client.neighbors.get(key);
			if (neighbor != null) {
				neighbor.up = false;
			}
		} else if (line.equals("LinkUp")) {
			Neighbor neighbor = client.neighbors.get(key);
			if (neighbor != null) {
				neighbor.up = true;
				neighbor.lastReceivedTime = System.currentTimeMillis();
				Route route = new Route();
				route.dest = key;
				route.link = key;
				route.cost = neighbor.weight;
				client.updateRoute(route);
			}
		} else {
			System.out.println("unknown " + line);
		}
		scanner.close();
	}
}













