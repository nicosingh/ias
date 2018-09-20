package org.eso.ias.cdb.topology

import org.eso.ias.cdb.pojos.AsceDao
import org.eso.ias.logging.IASLogger

/** The topology to forward IASIOs to ASCEs.
 * 
 * Objects of this class contains all information to move IASIOs 
 * (either coming from outside or produced by ACSEs) to ASCEs 
 * of the DASU and to the outside.
 * 
 * The topology is composed of trees of nodes.
 * Each IASIO in input is the root of a tree i.e.
 * the topology contains as many trees as IASIOs in input.
 *  
 * Each node of a tree can be a IASIO or a ASCE. 
 * A node is connected to another if:
 * - an IASIO is the input of a ASCE
 * - the output of a ASCE is the input of another ASCE
 * 
 * The inputs to the DASU are calculated from the inputs
 * of the ASCEs running into the DASU.
 * 
 * The topology provides information to the DASU on how and when move
 * IASIOs from the input to the ASCEs and from there to the outside.
 * 
 * The Topology is immutable. 
 * 
 * @constructor build a topology from the passed inputs and ASCEs
 * @param asces the ASCEs contained in the DASU
 * @param dasuId the identifier of the DASU
 * @param dasuOutputId the output generated by the DASU
 */
class DasuTopology(
    asces: List[AsceTopology],
    val dasuId: String,
    val dasuOutputId: String) {
  require(Option(asces).isDefined && !asces.isEmpty)
  require(Option(dasuId).isDefined)
  require(Option(dasuOutputId).isDefined)
  
  /** The logger */
  private val logger = IASLogger.getLogger(this.getClass)
  
  /** The output produced by all ASCEs 
   *  
   *  One of The ASCE must produce the output of the DASU
   */
  val asceOutputs: Set[String] = asces.map(asce => asce.output).toSet
  require(asceOutputs.contains(dasuOutputId),
      "The output ["+dasuOutputId+"] of the DASU ["+dasuId+"] is not produced by any of its ASCEs"+asceOutputs.mkString(","))
  
  // Ensure that the output produced by one ASCE is not produced by any other ASCE
  require(asces.size==asceOutputs.size,"Number of outputs of ASCEs and number of ASCEs mismatch")
  
  // Check if the output produced by the ASCEs running in this DASU
  // is used by other ASCEs in this same DASU. The only exception is
  // output produced by the last ASCE that is the output of the DASU itself
  require(asces.map(_.output).filterNot(_==dasuOutputId).toSet.subsetOf(asces.flatMap(_.inputs).toSet),
      "The ouput produced by some ASCEs of this DASU is unused")
  
  /** The inputs of the DASU
   * 
   * The inputs of the DASU are all the inputs of the ASCEs running
   * in the DASU that come from IASIO queue (i.e. not produced
   * by ASCEs running in the DASU).
   */
  val dasuInputs: Set[String] = asces.flatMap(asce => asce.inputs).filterNot(asceOutputs.contains).toSet
  
  /** The Ids of the ASCEs running in the DASU */
  val asceIds = asces.map(_.identifier).toSet
  require(asces.size==asceIds.size,"The list of topologies contains duplicate")
  
  /**
   * The map associating each ASCE ID to its topology
   */
  val asceTopology: Map[String,AsceTopology] = asces.foldLeft(Map.empty[String,AsceTopology]) { (z, asceTopology) =>
    z+(asceTopology.identifier -> asceTopology)
  }
  
  /**
   * The IDs of the inputs of each ASCE
   * 
   * The key is the ID of the ASCE;
   * the value is the set of the IDs of the inputs of the ASCE
   */
  val inputsOfAsce: Map[String, Set[String]] = {
    asceIds.foldLeft(Map.empty[String, Set[String]]) { (z, asceId) =>
      z+(asceId -> asceTopology(asceId).inputs)
    }
  }
  
  /**
   * The IDs of the ASCEs that require an input.
   * 
   * Note the this map also includes the IDs of the IASIOs
   * produced by ASCEs in the topology that are in input to other
   * ASCEs running in this DASU 
   * 
   * The key is the ID of the input;
   * the value is the list of the ACSE that require that input 
   */
  val ascesOfInput: Map[String, Set[String]] = {
    val allInputs: Set[String] = asces.flatMap(asce => asce.inputs).toSet
    
    def ascesWithInput(x: String) = {
      asces.foldLeft(Set.empty[String]) { (z, asce) =>
        if (asce.inputs.contains(x)) z+asce.identifier
        else z
      }
    }
    
    allInputs.foldLeft(Map.empty[String, Set[String]]) { (z,inputId) =>
      z+(inputId -> ascesWithInput(inputId))
    }
    
  }
  assert(
      ascesOfInput.keys.forall( id => !ascesOfInput(id).isEmpty),
      "Some of the inputs is not required by any ASCE")
  assert(ascesOfInput.keys.size>=dasuInputs.size)
    
  /** The trees of nodes from the inputs to the output of the DASU
   *   
   * There is one tree for each input of the DASU: each tree terminates
   * to the output of the DASU, if there are no cycles
   */
  require(isACyclic(),"Invalid cyclic graph")
  val trees: Set[Node] = buildTrees()
  trees.foreach(tree => assert(checkLastNodeId(tree,dasuOutputId)))

  /** A linearized version of the nodes in the topology:
   *  
   *  The trees in linearizedTrees represents the same graph
   *  as in trees but where each tree has at most one neighbor.
   *  
   *  It is a different representation of the trees but 
   *  it is often easier to reason in terms of linear graphs (like lists)
   *  instead of nodes with many neighbors.
   */
  val linearizedTrees: List[Node] = linearizeTrees(trees)
  linearizedTrees.foreach(tree => require(checkLastNodeId(tree,dasuOutputId)))
  linearizedTrees.foreach(tree => require(tree.nodeType==NodeType.IASIO))
  
  /** The max depth of the trees in the topology
   *  
   *  This is the maximal number of ASCEs to activate to produce 
   *  the output of the DASU.
   */
  val maxDepth: Int = trees.map(t => getDepth(t,math.max)).fold(0){ (x,y) => math.max(x,y) }
  
  /** The min depth of the trees in the topology
   *  
   *  This is the minimal number of ASCEs to activate to produce 
   *  the output of the DASU.
   */
  val minDepth: Int = trees.map(t => getDepth(t,math.min)).fold(maxDepth){ (x,y) => math.min(x,y) }
  
  /**
   * The levels used to propagate inputs inside the DASU.
   * The string in the levels(i) are the identifiers of the ASCEs
   * belonging to that level. 
   * 
   * In general, the ASCEs belonging to level l are those who require inputs 
   * coming from outside (IASIO queues) or generated by ASCEs 
   * belonging to the levels [0,l-1]
   *
   * IASIOs from outside (i.e. red from the IASIO queues) are the inputs of ASCEs at level 0; 
   * the same IASIOs plus those generated by ASCEs at level 0 goes ASCEs at level 1 
   * and so on.
   */
  val levels: List[Set[String]] = buildLevels().reverse
  assert(levels.size==maxDepth)
  
  /** The type of the nodes of the tree  */
  object NodeType extends Enumeration {
    type NType = Value
    // ASCE is the IASIO in output generated by a ASCE
    // IASIO is a value generated outside of the DASU
    val ASCE, IASIO = Value
  }
  
  /**
   * Auxiliary constructor
   * 
   * @param dId the identifier of the DASU
   * @param outId the identifier of the IASIO produced by the DASU
   * @param asceDaos the list of ASCE DAOs running in the DASU 
   */
  def this(
      dId: String,
      outId: String,
      asceDaos: List[AsceDao]) {
    this(asceDaos.map(new AsceTopology(_)),dId,outId)
  }
  
  /** The immutable node of the graph.
   *  
   * The identifier of the node is the ID of the IASIO either coming from plugins, 
   * other DASUs or produced by ASCEs running in this DASU.
   * 
   * The nodes constitute the trees of the topology
   *  
   * @constructor build a node with the passed id, type and connected nodes
   * @param id the identifier of the node
   * @param nodeType the type of the node
   * @param neighbors the connected nodes
   */
  class Node(val id: String, val nodeType: NodeType.NType, val neighbors:List[Node]) {
    override def toString = id+":"+nodeType+":"+neighbors.size
  }
  
  /** Check if the passed tree is linear
   *  i.e. if all its nodes have at most one neighbor
   *  
   *  @param node the root of the tree to check for linearity
   *  @return true if the tree is linear
   */
  def isLinearTree(node: Node): Boolean = {
    node.neighbors match {
      case Nil    => true
      case s::Nil => isLinearTree(s)
      case s::rest => false
    }
  }
  
  /** Calculate the max depth of a tree
   *  
   *  The max depth is the max number of nodes to traverse between 
   *  the passed root and a leaf.
   *  
   *  If the passed tree is linearized then there is only one
   *  possible depth but if the tree is not linearized then the 
   *  max depth is the depth of the longest tree that compose 
   *  the graph.
   * 
   * @param root the root node of the tree to calc the depth
   * @param f the function to calculate the depth like for example math.amx
   * @return the max depth of the tree
   */
  private def getDepth(root: Node, f: (Int, Int) => Int): Int = {
    root.neighbors match {
      case Nil => 0
      case s::Nil => 1+getDepth(s,f)
      case s::rest => f(1+getDepth(s,f), getDepth(new Node(root.id,root.nodeType,rest),f))
    }
  }
  
  /** Normalizes the passed trees
   *  
   *  @param trees the trees to normalize
   *  @return A set of linearized trees where each node has at most one neighbor
   */
  def linearizeTrees(trees: Set[Node]): List[Node] = {
    trees.toList.flatMap(root => linearizeTree(root,root,Nil))
  }

  /** Clone the passed linearized tree by replace the last node with the given node.
   *  
   *  This method clones the tree whose root is rootNode till it finds lastNode.
   *  In the cloned tree lastNode is not included but replaced by the replace
   *  node.
   *  
   *  For example, having a tree like A->B-C->D and calling this method with
   *  rootNode = A
   *  lastNode=C
   *  replace=X
   *  returns A->->X
   *  
   *  @param rootNode the root of the linearized tree to clone
   *  @param lastNode the last node (exclusive) to copy in the cloned tree
   *  @param replace the node to replce lastNode in the cloned tree
   *  @return a cloned tree of rootNode where lastNode is replaced 
   *          by the replace node
   */
  def cloneReplaceTree(rootNode: Node, lastNode: Node,replace: Node): Node = {
    assert(rootNode.neighbors.size==1)
    assert(rootNode.id!=lastNode.id)
    if (rootNode.neighbors.head.id==lastNode.id) new Node(rootNode.id,rootNode.nodeType,List(replace))
    else new Node(rootNode.id,rootNode.nodeType,List(cloneReplaceTree(rootNode.neighbors.head,lastNode,replace)))
   }
  
 
  /**
   * Linearize a tree producing many trees with only one child.
   * 
   * This method transform one tree where nodes have 1-to-many neighbohrs
   * in many trees where each node has one neighbor.
   * 
   * For example a tree like this, where A has 2 neighbors B and D
   * A->B->C
   *  ->D
   * is transformed in 2 trees: A->B->C and A->D
   * 
   * @param root the root node of the tree
   * @param node the accumulator i.e. the node  currently checked 
   *             for linearity
   */
  def linearizeTree(root: Node, node: Node, trees: List[Node]): List[Node] = {
    (node.neighbors,node.nodeType) match {
      case (Nil,_)    => List(root):::trees
      case (s::Nil,_) => linearizeTree(root,s,trees)
      case (s::rest,NodeType.IASIO) => {
        // New root 
        val newRoot: Node = new Node(root.id,root.nodeType,List(s))
        val linearizedNewRoot = linearizeTree(newRoot, newRoot,trees)
        val newRootRest: Node = new Node(root.id,root.nodeType,rest)
        val linearizeRest = linearizeTree(newRootRest,newRootRest,trees)
        linearizedNewRoot:::linearizeRest:::trees
        }
      case (s::rest,NodeType.ASCE) => {
        // Root remains the same
        val newNode: Node = new Node(node.id,node.nodeType,List(s))
        val newTree = cloneReplaceTree(root,newNode,newNode)
        val linearizedNewNode = linearizeTree(newTree,newTree,trees)
        
        val newNodeRest: Node = new Node(node.id,node.nodeType,rest)
        val newRestTree = cloneReplaceTree(root,newNodeRest,newNodeRest)
        val linearizedRest = linearizeTree(newRestTree,newRestTree,trees) 
        linearizedNewNode:::linearizedRest:::trees
      }
      case (List(_,_),_) => {
        Nil
      }
    }
  }
  
  /** Build the tree of the passed Node.
   *  
   * @param node the node to build the tree
   */
  private def buildTreeOfNode(node: Node): Node = {
    assert(node.neighbors.isEmpty)
    // The IDs of the ASCEs to which this node is connected 
    val connections: List[AsceTopology] = asces.filter(a => a.isRequiredInput(node.id))
    val childs: List[Node] = connections.map(asce => buildTreeOfNode(new Node(asce.output,NodeType.ASCE,List())))
    
    val ret = new Node(
        node.id, 
        node.nodeType, 
       childs)
    
    ret
  }
  
  /** Build the trees from the IASIOs in input to the ASCEs.
   *  
   *  The root of each tree is a input (IASIO).
   */
  private def buildTrees(): Set[Node] = {
    dasuInputs.map(inId => buildTreeOfNode(new Node(inId, NodeType.IASIO, List())))
  }
  
  /** Check if the last node of the passed tree terminates with the passed ID
   *  
   *  Each node of a tree begins with an input and terminate with the 
   *  output generated by the last ASCE i.e. the output of the DASU
   */
  private def checkLastNodeId(node: Node, outputId: String): Boolean = {
    node.neighbors match {
      case Nil => node.id==outputId
      case s => s.forall(n => checkLastNodeId(n,outputId))
    }
    
  }
  
  /** Create a string describing a tree
   *  
   *  @param node the root of the tree
   *  @return a String describing the tree with the passed node as root
   */
  def printTree(node: Node):String = {
    val ret = new StringBuilder(node.toString())
    ret.append(" isLinear=")
    ret.append(isLinearTree(node))
    val childStrs: List[String] = node.neighbors.map(n => printTree(n))
    if (!childStrs.isEmpty) {
      ret.append(" -> [")
      ret.append(childStrs.mkString(","))
      ret.append(']')
    }
    ret.toString()
  }
  
  /** Builds a human readable string describing the topology */
  override def toString = {
    val ret = new StringBuilder("Topology of [")
    ret.append(dasuId)
    ret.append("]: output id=")
    ret.append(dasuOutputId)
    ret.append(" levels=")
    ret.append(levels.size)
    ret.append("\nASCES: ")
    asces.foreach(asce => ret.append("\n\t"+asce.toString))
    ret.append("\nInputs of DASU: ")
    ret.append(dasuInputs.toList.sorted.mkString(", "))
    ret.append("\nTrees:")
    ret.append(trees.foreach( node => {
      ret.append("\n\t")
      ret.append(printTree(node))
    }))
    ret.append("\nLevels")
    ret.append(levels.zipWithIndex.foreach( lvlWithIdx => {
      ret.append("\n\t Level(")
      ret.append(lvlWithIdx._2)
      ret.append(")=")
      ret.append(lvlWithIdx._1.toList.sorted.mkString(", "))
    }))
    ret.toString()
  }
  
  /** Check the a-cyclicity of the graph.
   *  
   *  The method repeats the same test for each input
   *  of the DASU
   */
  private def isACyclic(): Boolean = {

    /** The check is done checking each input and the
     *  ASCEs that need it. Then the output produced
     *  by a ASCE is checked against the ASCEs that need it
     *  and so on until there are no more ASCEs.
     *  The ASCEs and input already checked are put in the acc
     *  set.
     * 
     * A cycle is resent if the passed input
     * is already present in the acc set.
     */
    def iasAcyclic(in: String, acc: Set[String]): Boolean = {
      // List of ASCEs that wants the passed input
      val ascesThatWantThisInput: List[AsceTopology] = asces.filter(asce => asce.isRequiredInput(in))
      
      // The outputs generated by all the ASCEs that want this output
      val outputs: List[String] = ascesThatWantThisInput.map( asceDao => asceDao.output)
      if (outputs.isEmpty) true
      else {
        outputs.forall(s => {
          val newSet = acc+in
          !newSet.contains(s) && iasAcyclic(s,newSet)
        })
      }
    }
    
    dasuInputs.forall(input => iasAcyclic(input, Set()))
    
  }
  
  /**
   * Build the levels of ASCE to move IASIOs from one ASCE to another
   * till the last one.
   * 
   * Flow of IASIOs (coming from outside or generated from ASCEs
   * running in this DASU) goes from level 0 to the last level.
   * ASCEs at level 0 are those that depends only on output
   * coming from outside.
   * ASCEs at level 1 are those whose inputs come from outside or from the ASCEs
   * in previous level) and so on.
   * 
   * In general ASCE at level l are those who require inputs coming from outside
   * or generated in one of the levels [0,l-1]
   * 
   * @return an array of identifiers of ASCEs
   */
  def buildLevels(): List[Set[String]] = {
    
    def allocate(availableInputs: Set[String], allocatedAsces: Set[String], acc: List[Set[String]]):List[Set[String]] = {
      
      // The ASCEs that require only the inputs in availableInputs
      val s: List[AsceTopology] = asces.filter(asce => asce.inputs.forall(input => availableInputs.contains(input)))
      // The identifiers of the ASCEs belonging to this level
      val asceIds = s.map(asce => asce.identifier).toSet--allocatedAsces
      // The new available inputs enriched with the outputs
      // generated by the ASCEs at this level
      val newInputs = availableInputs++s.map(asce => asce.output)
      
      val newAllocatedAsces = allocatedAsces++asceIds
      val ascesToAllocate = asces.map(_.identifier).filterNot(id => newAllocatedAsces.contains(id)).toSet
      
      if (ascesToAllocate.isEmpty) asceIds::acc
      else {
        allocate(newInputs, newAllocatedAsces, asceIds::acc)
      }
    }
    
    allocate(dasuInputs,Set[String](),Nil)
  }
  
  /**
   * Return the ID of the ASCE whose output has the passed ID.
   * 
   * @param outputId The id of the of the output
   * @return the ID, if any, of the ASCE that produces the passed output ID
   */
  def asceProducingOutput(outputId: String): Option[String] = {
    require(Option(outputId).isDefined && !outputId.isEmpty(),"Invalid output identifier")
    
    asces.find(_.output==outputId).map(_.identifier)
  }
}
