// Copyright (c) 2015, maldicion069 (Cristian Rodríguez) <ccrisrober@gmail.con>
//
// Permission to use, copy, modify, and/or distribute this software for any
// purpose with or without fee is hereby granted, provided that the above
// copyright notice and this permission notice appear in all copies.
//
// THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
// WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
// MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
// ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
// WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
// ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
// OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.package com.example

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovyx.gpars.actor.DefaultActor
import groovyx.gpars.actor.DynamicDispatchActor

import java.net.*
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import org.codehaus.groovy.runtime.metaclass.ConcurrentReaderHashMap;

public class Interrupts {

	static final defaultCondition = { -> true; };
	static final defaultOnInterrupt = { -> };
	static final defaultOnEnd = { -> };

	static guarded(final def args) {
		final def work = args.work;
		final def condition = args.condition ?: defaultCondition;
		final def onInterrupt = args.onInterrupt ?: defaultOnInterrupt;
		final def onEnd = args.onEnd ?: defaultOnEnd;

		while(!Thread.currentThread().interrupted && condition()) {
			try {
				work();
			}
			catch(InterruptedException ie) {
				Thread.currentThread().interrupt();
			}
		}

		if(Thread.currentThread().interrupted) {
			onInterrupt();
		}

		onEnd();
	}

	static Runnable runnable(final def args) {
		return { -> guarded(args); } as Runnable;
	}
}

// DynamicDispatchActor permite varios tipos de onMessage
class ClientManager extends DynamicDispatchActor {
	Thread socketThread
	
	def void onMessage(SendThread st) {
		socketThread = st.thread
	}

	def void onMessage(HandleRequest req) {
		def handler = new MyClientHandler(req.clientSocket, req.isGame)
		handler.start()
		handler << new InitMessage()
	}
}

public class MySingleton {
	private static final MySingleton instance = new MySingleton()
	
	def positions = [:] as ConcurrentHashMap
	def clients = [:] as ConcurrentHashMap
	def maps = []
	def counter = new AtomicInteger()
	
	private MySingleton() {
	}
	
	def static MySingleton getInstance() {
		return instance;
	}
}

public class InitMessage {
}

public class SendMessage {
	String message
}

public class ReceiveMessage {
	String message
}

import groovy.transform.EqualsAndHashCode
import groovy.lang.Tuple
@EqualsAndHashCode
class MyClientHandler extends DynamicDispatchActor {
	public boolean equals(java.lang.Object other) {
		if (other == null) return false
		if (this.is(other)) return true
		if (!(other instanceof MyClientHandler)) return false
		if (!other.canEqual(this)) return false
		if (this.ident != other.ident) return false
		return true
	}
	
	public boolean canEqual(java.lang.Object other) {
		return other instanceof MyClientHandler
	}

	def MyClientHandler(final Socket s, final boolean isGame) {
		this.clientSocket = s
		this.ident = MySingleton.getInstance().counter.getAndIncrement().toString() //port.toString()
		println this.clientSocket.isClosed()
		this.input = new BufferedReader(
			new InputStreamReader(clientSocket.inputStream))
		this.output = new PrintWriter(clientSocket.outputStream, true)
		
		this.jsonSlurper = new JsonSlurper()
		this.close = false
	}
	final Socket clientSocket
	final String ident
	final BufferedReader input
	final PrintWriter output
	final JsonSlurper jsonSlurper
	final boolean isGame
	private boolean close
	
	String request() {
		def is = this.clientSocket.inputStream;
		def ary = new byte[4096];
		def strBuilder = new StringBuilder();
		int read = is.read(ary);
		def str = new String(ary, 0, read, 'US-ASCII');
		return str;
	}
	
	def void onMessage(InitMessage im)  {
		try {
			//def req = this.input.readLine()
			if (this.input.ready()) {
				def req = this.input.readLine() //request()
				this << new ReceiveMessage(message: req)
			} else {
				this << im
			}
		} catch(SocketException) {
		
		}
	}
	
	def void onMessage(SendMessage sm) {
		this.output.println(sm.message)
	}
	
	def void onMessage(ReceiveMessage rm) {
		println "${rm.message} by ${this.ident}"
		def data = rm.message
		def object = this.jsonSlurper.parseText(data)
		println "RECIBIDO " + object
		switch (object.Action) {
			case "initWName":
				def name = object.Name
				
				def ou = new ObjectUser([Id: this.ident.toInteger(), PosX: 5*64, PosY: 5*64])
				def json = new JsonBuilder()
				def map = MySingleton.getInstance().maps.get(0)
				json Action: "sendMap",
					Map: map,
					X: ou.PosX,
					Y: ou.PosY,
					Id: ou.Id,
					Users: MySingleton.getInstance().positions.values()
				this.output.println(json.toString())
				MySingleton.getInstance().positions.put(this.ident, ou)

				println "Tenemos " + MySingleton.getInstance().clients.size() + " actores"
				
				//def json2 = new JsonBuilder()
				
				MySingleton.getInstance().clients.put(this.ident, this)
				
				if(this.isGame) {
					json Action: "new",
						Id: ou.Id,
						PosX: ou.PosX,
						PosY: ou.PosY
					data = json.toString()
				}
				break
			case "move":
				def px = object.Pos.X
				def py = object.Pos.Y
				def cl = MySingleton.getInstance().positions.get(this.ident)
				cl.PosX = px
				cl.PosY = py
				MySingleton.getInstance().positions.put(this.ident, cl)
				if(!this.isGame) {
					this.output.println(data)
				}
				break
			case "exit":
				MySingleton.getInstance().positions.remove(this.ident)
				MySingleton.getInstance().clients.remove(this.ident)
				def json = new JsonBuilder()
				if(this.isGame) {
					json Action: "exit",
						Id: this.ident.toInteger()
					data = json.toString()
				} else {
					json Action: "exit",
						Id: "Me"
					data = json.toString()
					this.output.println(data)
				}
				this.clientSocket.close()
				close = true
				break
		}
		if(this.isGame) {
			MySingleton.getInstance().clients.values().each { 
				actor -> 
					if(this.ident != actor.ident) {
						actor.send(new SendMessage(message: data))
						//actor.output.println(data)
					}
			}
		}
		if(!this.close) {
			this << new InitMessage()
		}
	}
	
	/*def void onException(Throwable th) {
		println "Close database connection"
		println th
		println "Release other resources"
		println "Bye"
	}*/
}

class SendThread {
	public SendThread(final Thread th) {
		this.thread = th
	}
	final Thread thread
}

class HandleRequest {
	public HandleRequest(final Socket s, final boolean isGame) {
		this.clientSocket = s
		this.isGame = isGame
	}
	final Socket clientSocket
	final boolean isGame
}

def runServer(int port, ClientManager mng, final boolean isGame) {
	def serverSocket = new ServerSocket(port, 0, InetAddress.getByName("0.0.0.0"))
	def onEnd = { -> println 'Shutting down server'; serverSocket.close }
	
	def serverAction = {
		try {
			mng << new HandleRequest(serverSocket.accept(), isGame)
		} catch(SocketTimeoutException err) {}
	}
	def runnable = Interrupts.runnable(work: serverAction, onEnd: onEnd);
	def thread = new Thread(target: runnable);
	mng << new SendThread(thread);
	thread.start();
	def gameMode = isGame ? "Game Mode" : "Test Mode"
	println "Server started in ${gameMode}"
	return thread;
}

MySingleton.getInstance().maps.push(
	new MapData(
		Id: 1,
		MapFields:  "1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1," +
		            "1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1," +
		            "1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 4, 0, 0, 0, 1," +
		            "1, 1, 0, 0, 0, 0, 0, 0, 0, 5, 5, 7, 5, 5, 5, 5, 1, 1, 0, 0, 0, 0, 0, 0, 5, 5, 5, 5, 5, 5, 5, 1," +
		            "1, 1, 0, 0, 4, 6, 5, 0, 0, 0, 1, 1, 1, 0, 0, 0, 0, 1, 0, 0, 4, 0, 5, 5, 5, 0, 8, 8, 8, 0, 0, 1," +
		            "1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 4, 0, 0, 0, 1, 1, 0, 5, 5, 5, 0, 0, 0, 8, 8, 8, 4, 0, 1," +
		            "1, 0, 1, 0, 0, 0, 0, 4, 0, 0, 1, 1, 1, 1, 4, 0, 0, 0, 1, 0, 5, 0, 4, 4, 0, 0, 8, 8, 8, 1, 4, 1," +
		            "4, 0, 1, 0, 0, 0, 0, 4, 4, 0, 1, 1, 1, 1, 1, 1, 4, 0, 1, 0, 5, 0, 4, 4, 4, 0, 8, 8, 8, 1, 1, 1," +
		            "1, 0, 1, 0, 0, 4, 4, 4, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 1, 0, 5, 4, 4, 4, 0, 0, 0, 0, 1, 1, 1, 1," +
		            "1, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 0, 0, 1, 1, 1, 1, 1," +
		            "1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 4, 0, 0, 0, 1," +
		            "1, 1, 0, 0, 0, 0, 0, 0, 5, 5, 5, 5, 5, 5, 5, 5, 1, 1, 0, 0, 0, 0, 0, 0, 5, 5, 5, 5, 5, 5, 5, 5," +
		            "1, 1, 0, 0, 4, 0, 5, 5, 5, 0, 1, 1, 1, 0, 0, 0, 0, 1, 0, 0, 4, 0, 5, 5, 5, 0, 1, 1, 1, 0, 0, 1," +
		            "1, 1, 1, 0, 5, 5, 5, 0, 0, 0, 1, 1, 1, 4, 0, 0, 0, 1, 1, 0, 5, 5, 5, 0, 0, 0, 1, 1, 1, 4, 0, 1," +
		            "1, 0, 1, 0, 5, 0, 4, 4, 0, 0, 1, 1, 1, 1, 4, 0, 0, 0, 1, 0, 5, 0, 4, 4, 0, 0, 1, 1, 1, 1, 4, 1," +
		            "4, 0, 1, 0, 5, 0, 4, 4, 4, 0, 1, 1, 1, 1, 1, 1, 4, 0, 1, 0, 5, 0, 4, 4, 4, 0, 1, 1, 1, 1, 1, 1," +
		            "1, 1, 1, 1, 1, 4, 4, 4, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 4, 4, 4, 1, 1, 1, 1, 1, 1, 1, 1," +
		            "1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1," +
		            "1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1," +
		            "1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1," +
		            "1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1," +
		            "1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1," +
		            "1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1," +
		            "1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1," +
		            "1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,",
		Width: 32,
		Height: 25
	)
)

def br = new BufferedReader(new InputStreamReader(System.in))
print '[S/s] Game Mode / [_] Test Game'
def mode = br.readLine()
def isGame = false
if(mode.toLowerCase() == "s") {
	isGame = true
}

ClientManager mng = new ClientManager()
mng.start()
runServer(8090, mng, isGame).join()
mng.join