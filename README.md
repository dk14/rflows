# rflows

This is embedded reactive and typesafe Scala-based DSL for declaring flows using functional composition.


####Flow
sequentially executes several acts, splitters, aggregators or other flows

Define routing (like `Routing1`)
 
To create new application-level flow - just extend `InstrumentedRouting` trait and define your flow inside:

    trait Routing1 extends InstrumentedRouting[Request] with MyService1 with MyService2 {
      val Flow1 = Act("act1", handler1) |> Act("act2", handler2)
    }

define flow as composition (`|>`) of acts, splitters and aggregators

##### Simple Act 
just executes a processor

    Act.simple("act1", handler1)

`handler1` takes input message and returns output message

##### Act
executes a processor and moving to next act (or returning result) in reactive way
    
    Act("act1", handler1)

`handler1` takes input message and returns future of output message, so next act in chain (act2) is executed only when future completes. In combination with scala's Futures compositionIt gives you a way ackuire asynchronous services and build processing in reactive way.

##### Split
splits (optionally) the message to several ones and sends each part to its (dynamically chosen) flow in reactive way. It's also possible to send same message to different flows.

    Split("split1", splitter1)

    def splitter1(in: Data[Request]) = Seq(Future(in) -> SubFlow1, Future(in) -> SubFlow2) //example with splitting flow but not the message
 
    //or
 
    def splitter1(in: Data[Request]) = Seq(Future(in.part1) -> SubFlow1, Future(in.part2) -> SubFlow2) //example with splitting flow but not the message

`splitter1` function takes message and returns list of directives, each directive is Future[Message] -> Flow, which means that it contains a new message (may be just a copy of input and flow to process this message.

>All flows (returned by splitter) should be part of same group (see below). For instance, SubFlow1 and SubFlow2 are parts of FlowGroup1 (see the diagram)
>Otherwise you get scary compilation error, which is intentional protection from SubFlow Hell'

##### Route
route process a message and route the result to dynamically chosen flow (in reactive way)
Same as `Split` but `route1` returns list of only one directive (with flow chosen based on input message) to process. It can be implemented with same Split construction:

  Split("route1", router1)

  def router1(in: Data[Request]) = if (in.isForSubFlow1)  Seq(Future(in.data) -> SubFlow1) else Seq(Future(in.data) -> SubFlow2)

##### FlowGroup and SubFlows
groups several sub-flows to be used inside one `Split`

  implicit object FlowGroup1 extends Group {
    val SubFlow1 = Act("act3", handler3) |> Act("act4", handler4) tagged //do not forget "tagged" word !!
    val SubFlow2 = Act("act5", handler5) tagged
  }

##### Aggregate
aggregates messages

  Aggregate("aggregate1", aggregator1)

#### Basic terms


#####Data

Data is a wrapper around message. It contains the message itself, and also its Context and Meta-info

#####Context

Context is an object shared between all Flows(like Flow1). inside one Routing (like Routing1).
You can specify its type when extending InstrumentedRouting trait (see below) and specify the object itself when passing incoming message to a flow, like `Flow(incomingMessage, context)`
Usually the context is current request, so just `Flow(incomingMessage, incomingMessage)`

#####Meta

Meta - is meta-information which contains current executing flow itself (including name), so you can propagate it to handler, services etc. Used for metrics and logging to define the concrete flow (act, splitter or aggregator) where some measured/logged event is actually takes place.

####See also

See `RoutingDSLTest.scala` for complete working example 

TODO: Big Flow Decomposition

TODO: "how to write handler" examples

TODO: Flow and Routing terms

TODO: how to register before, after, failure handlers
