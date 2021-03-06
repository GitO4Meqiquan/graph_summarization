package graph_summarization;

import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.set.hash.TIntHashSet;
import it.unimi.dsi.webgraph.ImmutableGraph;
import org.javatuples.Pair;

import java.util.*;

public class Summary {
    // webgraph 框架中的不变图对象，可以用来获取图的顶点和边属性
    ImmutableGraph Gr;
    // 图的顶点数量
    int n;
    // 超点数组，用来指明每个顶点的超点编号，如 S[3]=2 表示原图顶点3的超点编号是2
    int[] S;
    // 记录超点的第一个顶点，如 I[3]=5 表示超点编号3的第一个子顶点是5，而 I[4]=-1 则表示没有编号是4的超点
    int[] I;
    // 记录同属一个超点的下一个顶点是哪个，就像是链表的next指针，如 I[3]=9 表示和顶点3同处一个超点的下一个顶点是9
    int[] J;

    // 用于记录每个超点的大小
    int[] supernode_sizes;

    // 下面是用于encode superEdges的数据结构
    HashMap<Integer, TIntArrayList> sn_to_n;
    ArrayList<Pair<Integer, Integer>> P;
    TIntArrayList Cp_0, Cp_1;
    TIntArrayList Cm_0, Cm_1;

    /**
     * 构造函数，用于初始化一些共同的结构
     *
     * @param basename 数据集的基本名字
     * @throws Exception
     */
    public Summary(String basename) throws Exception {
        // 调用 webgraph 框架来读取数据并构造图
        Gr = ImmutableGraph.loadMapped(basename);
        n = Gr.numNodes();

        S = new int[n];
        I = new int[n];
        J = new int[n];

        // 初始化每个顶点为一个超点，即分别设置 S[i]=i, I[i]=i 和 J[i]=i
        for (int i = 0; i < n; i++) {
            S[i] = i;  //Initial each node as a supernode
            I[i] = i;
            J[i] = -1;
        }
    }

    /**
     * 更新超点，即合并两个超点，需要把第二个超点的所有顶点都合并到第一个超点里面(这里做了一个特殊处理，把编号大的合并到小的编号里面)
     *
     * @param super_node_a
     * @param super_node_b
     */
    protected void updateSuperNode(int super_node_a, int super_node_b) {
        int a = Math.min(super_node_a, super_node_b);
        int b = Math.max(super_node_a, super_node_b);
        int[] A_nodes = recoverSuperNode(a);
        int[] B_nodes = recoverSuperNode(b);
        J[A_nodes[A_nodes.length - 1]] = I[b];
        I[b] = -1;
        for (int i = 0; i < A_nodes.length; i++) S[A_nodes[i]] = I[a];
        for (int i = 0; i < B_nodes.length; i++) S[B_nodes[i]] = I[a];
    }

    /**
     * 计算超点包含多少个顶点
     *
     * @param super_node_id
     * @return
     */
    protected int superNodeLength(int super_node_id) {
        int counter = 0;
        int node = I[super_node_id];
        while (node != -1) {
            counter++;
            node = J[node];
        }
        return counter;
    }

    /**
     * 恢复超点，即计算超点包含哪些顶点
     *
     * @param super_node_id
     * @return
     */
    protected int[] recoverSuperNode(int super_node_id) {
        //Extracting the nodes belong to supernode key and return it (Arr)
        int length = superNodeLength(super_node_id);
        int[] nodes = new int[length];
        int counter = 0;
        int node = I[super_node_id];
        while (node != -1) {
            nodes[counter++] = node;
            node = J[node];
        }
        return nodes;
    }

    /**
     * 为一个小组Q内的所有超点建立HashMap<Integer, Integer>
     * 建立HashMap的目的是方便后续计算Jaccard Similarity 和 Saving
     * 一个超点的HashMap里面存储了 <u, num> 其中顶点 u 和该超点存在 num 条边相连
     *
     * @param Q          组内的所有超点编号
     * @param group_size 组的大小
     * @return
     */
    protected HashMap<Integer, HashMap<Integer, Integer>> createW(int[] Q, int group_size) {
        HashMap<Integer, HashMap<Integer, Integer>> w_All = new HashMap<Integer, HashMap<Integer, Integer>>();
        for (int i = 0; i < group_size; i++) {
            HashMap<Integer, Integer> w_Single = new HashMap<Integer, Integer>();
            int[] Nodes = recoverSuperNode(Q[i]);
            for (int j = 0; j < Nodes.length; j++) {
                int[] Neigh = Gr.successorArray(Nodes[j]);
                for (int k = 0; k < Neigh.length; k++) {
                    if (w_Single.containsKey(Neigh[k]))
                        w_Single.put(Neigh[k], w_Single.get(Neigh[k]) + 1);
                    else
                        w_Single.put(Neigh[k], 1);
                }
            }
            w_All.put(i, w_Single);
        }
        return w_All;
    }

    /**
     * 计算一个超点的w,具体是 <node_id, num>，即记录与超点存在边相连的顶点id以及边数量
     *
     * @param super_node_id 超点编号
     * @return
     */
    protected HashMap<Integer, Integer> createW(int super_node_id) {
        HashMap<Integer, Integer> w_Single = new HashMap<Integer, Integer>();
        int[] Nodes = recoverSuperNode(super_node_id);
        for (int j = 0; j < Nodes.length; j++) {
            int[] Neigh = Gr.successorArray(Nodes[j]);
            for (int k = 0; k < Neigh.length; k++) {
                if (w_Single.containsKey(Neigh[k]))
                    w_Single.put(Neigh[k], w_Single.get(Neigh[k]) + 1);
                else
                    w_Single.put(Neigh[k], 1);
            }
        }
        return w_Single;
    }

    /**
     * 更新超点的HashMap
     * 当合并两个超点时，其中一个超点的所有顶点并入到另一个超点里面，这时需要更新两个超点的HashMap，保证后续计算的准确性
     *
     * @param w_A 超点A的HashMap
     * @param w_B 超点B的HashMap
     * @return
     */
    protected HashMap<Integer, Integer> updateW(HashMap<Integer, Integer> w_A, HashMap<Integer, Integer> w_B) {
        HashMap<Integer, Integer> result = new HashMap<Integer, Integer>();
        for (Integer key : w_A.keySet()) {
            if (w_B.containsKey(key))
                result.put(key, w_A.get(key) + w_B.get(key));
            else
                result.put(key, w_A.get(key));
        }
        for (Integer key : w_B.keySet()) {
            if (w_A.containsKey(key))
                continue;
            result.put(key, w_B.get(key));
        }
        return result;
    }

    /**
     * 计算两个超点之间的Jaccard Similarity
     *
     * @param w_A 超点A的HashMap
     * @param w_B 超点B的HashMap
     * @return
     */
    protected double computeJacSim(HashMap<Integer, Integer> w_A, HashMap<Integer, Integer> w_B) {
        int down = 0;
        int up = 0;
        for (Integer key : w_A.keySet()) {
            if (w_B.containsKey(key)) {
                if (w_A.get(key) <= w_B.get(key)) {
                    up = up + w_A.get(key);
                    down = down + w_B.get(key);
                } else {
                    down = down + w_A.get(key);
                    up = up + w_B.get(key);
                }
            } else
                down = down + w_A.get(key);
        }
        for (Integer key : w_B.keySet()) {
            if (!(w_A.containsKey(key))) {
                down = down + w_B.get(key);
            }
        }
        return (up * 1.0) / (down * 1.0);
    }

    /**
     * 测试函数，目前存在问题
     *
     * @param w_A         超点A的HashMap
     * @param w_B         超点B的HashMap
     * @param supernode_A 超点A的编号
     * @param supernode_B 超点B的编号
     * @return
     */
    protected double computeSaving_test(HashMap<Integer, Integer> w_A, HashMap<Integer, Integer> w_B, int supernode_A, int supernode_B) {
        int[] nodes_A = recoverSuperNode(supernode_A);
        int[] nodes_B = recoverSuperNode(supernode_B);
        double cost_A = 0, cost_B = 0, cost_AUnionB = 0;
        // 这个HashMap用于存储与合并后的超点存在边相连的超点大小
        HashMap<Integer, Integer> candidate_size = new HashMap<Integer, Integer>();
        // 这个HashMap用于存储与超点A存在边相连的所有边数量
        HashMap<Integer, Integer> candidate_spA = new HashMap<Integer, Integer>();
        // 这个HashMap用于存储与超点B存在边相连的所有边数量
        HashMap<Integer, Integer> candidate_spB = new HashMap<Integer, Integer>();

        // 遍历w_A得到与超点A存在边相连的顶点u以及边数量num
        for (Integer key : w_A.keySet()) {
            if (!candidate_size.containsKey(S[key])) {
                int[] nodes = recoverSuperNode(S[key]);
                candidate_size.put(S[key], nodes.length);
                candidate_spA.put(S[key], w_A.get(key));
            } else {
                candidate_spA.put(S[key], candidate_spA.get(S[key]) + w_A.get(key));
            }
        }

        // 遍历w_B得到与超点B存在边相连的顶点u以及边数量num
        for (Integer key : w_B.keySet()) {
            if (!candidate_size.containsKey(S[key])) {
                int[] nodes = recoverSuperNode(S[key]);
                candidate_size.put(S[key], nodes.length);
                candidate_spB.put(S[key], w_B.get(key));
            } else if (candidate_spB.containsKey(S[key])) {
                candidate_spB.put(S[key], candidate_spB.get(S[key]) + w_B.get(key));
            } else {
                candidate_spB.put(S[key], w_B.get(key));
            }
        }

        // 开始计算超点A，B以及合并后超点的代价 cost_A, cost_B 和 cost_AUnionB
        for (Integer key : candidate_spA.keySet()) {
            if (key == supernode_A) { // in case of superloop
                if (candidate_spA.get(key) > (nodes_A.length * 1.0 * (nodes_A.length - 1)) / 4.0) {
                    cost_A += (nodes_A.length * 1.0 * (nodes_A.length - 1)) / 2.0 - candidate_spA.get(key) ;
                } else {
                    cost_A += candidate_spA.get(key);
                }
                continue;
            }
            if (candidate_spA.get(key) > (nodes_A.length * 1.0 * candidate_size.get(key)) / 2.0)
                cost_A += (candidate_size.get(key) * nodes_A.length) - candidate_spA.get(key) + 1;
            else
                cost_A += candidate_spA.get(key);

            if (key == supernode_B || key == supernode_A)
                continue;

            if (candidate_spB.containsKey(key)) {
                if ((candidate_spA.get(key) + candidate_spB.get(key)) > ((nodes_A.length + nodes_B.length) * 1.0 * candidate_size.get(key)) / 2.0)
                    cost_AUnionB += (candidate_size.get(key) * (nodes_A.length + nodes_B.length)) - candidate_spA.get(key) - candidate_spB.get(key) + 1;
                else
                    cost_AUnionB += candidate_spA.get(key) + candidate_spB.get(key);
            } else {
                if ((candidate_spA.get(key) > ((nodes_A.length + nodes_B.length) * 1.0 * candidate_size.get(key)) / 2.0))
                    cost_AUnionB += (candidate_size.get(key) * (nodes_A.length + nodes_B.length)) - candidate_spA.get(key) + 1;
                else
                    cost_AUnionB += candidate_spA.get(key);
            }
        }
        for (Integer key : candidate_spB.keySet()) {
            if (key == supernode_B) { // in case of superloop
                if (candidate_spB.get(key) > (nodes_B.length * 1.0 * (nodes_B.length - 1)) / 4.0) {
                    cost_B += (nodes_B.length * 1.0 * (nodes_B.length - 1)) / 2.0 - candidate_spB.get(key);
                } else {
                    cost_B += candidate_spB.get(key);
                }
                continue;
            }

            if (candidate_spB.get(key) > (nodes_B.length * 1.0 * candidate_size.get(key)) / 2.0)
                cost_B += (candidate_size.get(key) * nodes_B.length) - candidate_spB.get(key) + 1;
            else
                cost_B += candidate_spB.get(key);

            if (candidate_spA.containsKey(key) || key == supernode_A || key == supernode_B) {
                continue;
            } else {
                if ((candidate_spB.get(key) > ((nodes_A.length + nodes_B.length) * 1.0 * candidate_size.get(key)) / 2))
                    cost_AUnionB += (candidate_size.get(key) * (nodes_A.length + nodes_B.length)) - candidate_spB.get(key) + 1;
                else
                    cost_AUnionB += candidate_spB.get(key);
            }
        }

        int aUnionBEdges = 0;
        // 超点A存在自环边
        if (candidate_spA.containsKey(supernode_A))
            aUnionBEdges += candidate_spA.get(supernode_A);
        // 超点A和B存在超边
        if (candidate_spA.containsKey(supernode_B))
            aUnionBEdges += candidate_spA.get(supernode_B);
        // 超点B存在自环边
        if (candidate_spB.containsKey(supernode_B))
            aUnionBEdges += candidate_spB.get(supernode_B);
        if (aUnionBEdges > 0) {
            if (aUnionBEdges > ((nodes_A.length + nodes_B.length) * 1.0 * (nodes_A.length + nodes_B.length - 1.0)) / 4.0) {
                cost_AUnionB += ((nodes_A.length + nodes_B.length) * 1.0 * (nodes_A.length + nodes_B.length - 1.0)) / 2.0 - aUnionBEdges;

            } else {
                cost_AUnionB += aUnionBEdges;
            }
        }

        return 1 - (cost_AUnionB) / (cost_A + cost_B);
    }

    /**
     * 计算两个超点之间的Saving，即合并能带来的收益
     *
     * @param w_A         超点A的HashMap
     * @param w_B         超点B的HashMap
     * @param supernode_A 超点A的编号
     * @param supernode_B 超点B的编号
     * @return
     */
    protected double computeSaving(HashMap<Integer, Integer> w_A, HashMap<Integer, Integer> w_B, int supernode_A, int supernode_B) {
        int num_A = recoverSuperNode(supernode_A).length;
        int num_B = recoverSuperNode(supernode_B).length;
        double cost_A = 0, cost_B = 0, cost_AUnionB = 0;
        // 这个HashMap用于存储与合并后的超点存在边相连的超点大小
        HashMap<Integer, Integer> candidate_size = new HashMap<Integer, Integer>();
        // 这个HashMap用于存储与超点A存在边相连的所有边数量
        HashMap<Integer, Integer> candidate_spA = new HashMap<Integer, Integer>();
        // 这个HashMap用于存储与超点B存在边相连的所有边数量
        HashMap<Integer, Integer> candidate_spB = new HashMap<Integer, Integer>();

        // 遍历w_A得到与超点A存在边相连的顶点u以及边数量num
        for (Integer key : w_A.keySet()) {
            if (!candidate_size.containsKey(S[key])) {
                int[] nodes = recoverSuperNode(S[key]);
                candidate_size.put(S[key], nodes.length);
                candidate_spA.put(S[key], w_A.get(key));
            } else {
                candidate_spA.put(S[key], candidate_spA.get(S[key]) + w_A.get(key));
            }
        }

        // 遍历w_B得到与超点B存在边相连的顶点u以及边数量num
        for (Integer key : w_B.keySet()) {
            if (!candidate_size.containsKey(S[key])) {
                int[] nodes = recoverSuperNode(S[key]);
                candidate_size.put(S[key], nodes.length);
                candidate_spB.put(S[key], w_B.get(key));
            } else if (candidate_spB.containsKey(S[key])) {
                candidate_spB.put(S[key], candidate_spB.get(S[key]) + w_B.get(key));
            } else {
                candidate_spB.put(S[key], w_B.get(key));
            }
        }

        // 开始计算超点A，B以及合并后超点的代价 cost_A, cost_B 和 cost_AUnionB
        for (Integer key : candidate_spA.keySet()) {
            int E = candidate_spA.get(key);
            double compare = key == supernode_A ? ((num_A * 1.0 * (num_A - 1)) / 2.0) : (num_A * 1.0 * candidate_size.get(key));
            cost_A += (E <= compare / 2.0) ? (E) : (1 + compare - E);

            if (key == supernode_B || key == supernode_A)
                continue;

            E += candidate_spB.containsKey(key) ? candidate_spB.get(key) : 0;
            compare = key == supernode_A ? (((num_A + num_B) * 1.0 * (num_A + num_B - 1)) / 2.0) : ((num_A + num_B) * 1.0 * candidate_size.get(key));
            cost_AUnionB += (E <= compare / 2.0) ? (E) : (1 + compare - E);
        }
        for (Integer key : candidate_spB.keySet()) {
            int E = candidate_spB.get(key);
            double compare = key == supernode_B ? ((num_B * 1.0 * (num_B - 1)) / 2.0) : (num_B * 1.0 * candidate_size.get(key));
            cost_B += (E <= compare / 2.0) ? (E) : (1 + compare - E);

            if (key == supernode_B || key == supernode_A)
                continue;

            if (!candidate_spA.containsKey(key)) {
                compare = key == supernode_B ? (((num_A + num_B) * 1.0 * (num_A + num_B - 1)) / 2.0) : ((num_A + num_B) * 1.0 * candidate_size.get(key));
                cost_AUnionB += (E <= compare / 2.0) ? (E) : (1 + compare - E);
            }
        }

        int E = 0;
        // 超点A存在自环边
        if (candidate_spA.containsKey(supernode_A))
            E += candidate_spA.get(supernode_A);
        // 超点A和B存在超边
        if (candidate_spA.containsKey(supernode_B))
            E += candidate_spA.get(supernode_B);
        // 超点B存在自环边
        if (candidate_spB.containsKey(supernode_B))
            E += candidate_spB.get(supernode_B);
        if (E > 0) {
            double compare = ((num_A + num_B) * 1.0 * (num_A + num_B - 1)) / 2.0;
            cost_AUnionB += (E <= compare / 2.0) ? (E) : (1 + compare - E);
        }
        return 1 - (cost_AUnionB) / (cost_A + cost_B);
    }

    /**
     * 顶点初始化的阶段，Greedy算法需要进行重载
     */
    public double initialPhase(double threshold) {
        return 0.0;
    }

    /**
     * 顶点分组的阶段，SWeG和LDME算法需要进行重载
     */
    public double dividePhase() {
        return 0.0;
    }

    /**
     * 顶点合并的阶段，SWeG和LDME算法需要进行重载
     *
     * @param threshold 合并阶段的阈值，低于阈值的顶点对不合并
     */
    public double mergePhase(double threshold) {
        return 0.0;
    }

    /**
     * 编码阶段：
     * (1)先对超点进行编码，即判断有多少个超点，并且记录每个超点对应哪些顶点集合
     * (2)接着对超边进行编码
     */
    public double encodePhase() {
        System.out.println("# Encode Phase");
        long startTime = System.currentTimeMillis();
        supernode_sizes = new int[n];
        sn_to_n = new HashMap<>();
        P = new ArrayList<>();
        Cp_0 = new TIntArrayList();
        Cp_1 = new TIntArrayList();
        Cm_0 = new TIntArrayList();
        Cm_1 = new TIntArrayList();
        int supernode_count = 0;
        int[] S_copy = Arrays.copyOf(S, S.length);

        // 对超点进行编码
        for (int i = 0; i < n; i++) {
            // 如果存在编号为i的超点
            if (I[i] != -1) {
                // 获取超点i包含的所有顶点
                int[] nodes_inside = recoverSuperNode(i);
                TIntArrayList nodes_inside_list = new TIntArrayList();
                supernode_sizes[supernode_count] = nodes_inside.length;
                for (int j = 0; j < nodes_inside.length; j++) {
                    nodes_inside_list.add(nodes_inside[j]);
                    // Rename the superNode from S_copy[nodes_inside[j]] to supernode_count
                    S_copy[nodes_inside[j]] = supernode_count;
                }
                sn_to_n.put(supernode_count, nodes_inside_list);    // sn_to_n record the relation between (superNode, nodes_list)
                supernode_count++;
            }
        }

        // Encode superEdges
        for (int A = 0; A < supernode_count; A++) {
            // get all nodes in superNode A
            TIntArrayList in_A = sn_to_n.get(A);
            // edges_count[B] means the num of edges between superNode A and B
            int[] edges_count = new int[supernode_count];
            // edges_list[B] is the sets of edges between superNode A and B
            HashSet<?>[] edges_list = new HashSet<?>[supernode_count];
            // record the superNode which has one edge with A at least
            TIntHashSet has_edge_with_A = new TIntHashSet();

            // find each edges between superNode A and other superNodes
            for (int a = 0; a < in_A.size(); a++) {
                int node = in_A.get(a);
                int[] neighbours = Gr.successorArray(node);

                for (int i = 0; i < neighbours.length; i++) {
                    // B = S_copy[neighbours[i]]
                    edges_count[S_copy[neighbours[i]]]++;
                    // if this B has not already been processed
                    if (S_copy[neighbours[i]] >= A) {
                        has_edge_with_A.add(S_copy[neighbours[i]]);
                    }
                    // record the edge<node, neighbours[i]> in the edges_list[B]
                    if (edges_list[S_copy[neighbours[i]]] == null) {
                        edges_list[S_copy[neighbours[i]]] = new HashSet<Pair<Integer, Integer>>();
                    }
                    ((HashSet<Pair<Integer, Integer>>) edges_list[S_copy[neighbours[i]]]).add(new Pair(node, neighbours[i]));
                } // for i
            } // for A

            // process each superNode pair <A, B> at least one edge between them
            TIntIterator iter = has_edge_with_A.iterator();
            while (iter.hasNext()) {
                int B = (Integer) iter.next();
                double edge_compare_cond = 0;
                // figure out which situation: 1. A and A  2. A and B (differ from A)
                if (A == B) {
                    edge_compare_cond = supernode_sizes[A] * (supernode_sizes[A] - 1) / 4;
                } else {
                    edge_compare_cond = (supernode_sizes[A] * supernode_sizes[B]) / 2;
                }
                // do not add superEdge between superNode A and B
                if (edges_count[B] <= edge_compare_cond) {
                    // Add all edges between A and B to C+
                    for (Pair<Integer, Integer> edge : ((HashSet<Pair<Integer, Integer>>) edges_list[B])) {
                        // Cp_0 store the source node of edge, Cp_1 store the target node of edge
                        Cp_0.add(edge.getValue0());
                        Cp_1.add(edge.getValue1());
                    }

                } else { // add a superEdge between A and B to P and add the difference to C-
                    P.add(new Pair(A, B));
                    // get all nodes in superNode B
                    TIntArrayList in_B = sn_to_n.get(B);
                    // process each possible pair <a,b> where a in superNode A and b in superNode B
                    for (int a = 0; a < in_A.size(); a++) {
                        for (int b = 0; b < in_B.size(); b++) {
                            Pair<Integer, Integer> edge = new Pair(in_A.get(a), in_B.get(b));
                            // edge<a,b> do not exist truly, but we need to store it to the C-
                            if (!((HashSet<Pair<Integer, Integer>>) edges_list[B]).contains(edge)) {
                                // Cm_0 store the source node of edge, Cm_1 store the target node of edge
                                Cm_0.add(in_A.get(a));
                                Cm_1.add(in_B.get(b));
                            }
                        } // for b
                    } // for a
                } // else
            } // for B
        } // for A

        return (System.currentTimeMillis() - startTime) / 1000.0;
    }

    /**
     * 编码阶段，在LDME算法中同样改进了这个地方，比上面的方法速度更快：
     * (1)先对超点进行编码，即判断有多少个超点，并且记录每个超点对应哪些顶点集合
     * (2)接着对超边进行编码
     */
    public double encodePhase_new(){
        System.out.println("# Encode Phase");
        long startTime = System.currentTimeMillis();
        supernode_sizes = new int[n];
        sn_to_n = new HashMap<>();
        P = new ArrayList<>();
        Cp_0 = new TIntArrayList();
        Cp_1 = new TIntArrayList();
        Cm_0 = new TIntArrayList();
        Cm_1 = new TIntArrayList();
        int edges_compressed = 0;
        int supernode_count = 0;
        int[] S_copy = Arrays.copyOf(S, S.length);

        for (int i = 0; i < n; i++) {
            if (I[i] != -1) {
                int[] nodes_inside = recoverSuperNode(i);
                TIntArrayList nodes_inside_list = new TIntArrayList();
                supernode_sizes[supernode_count] = nodes_inside.length;
                for (int j = 0; j < nodes_inside.length; j++) {
                    nodes_inside_list.add(nodes_inside[j]);
                    S_copy[nodes_inside[j]] = supernode_count;
                }
                sn_to_n.put(supernode_count, nodes_inside_list);
                supernode_count++;
            }
        }

        LinkedList<FourTuple> edges_encoding = new LinkedList<FourTuple>();
        for (int node = 0; node < n; node++) {
            for(int neighbour : Gr.successorArray(node)) {
                if (S_copy[node] <= S_copy[neighbour]) {
                    edges_encoding.add(new FourTuple(S_copy[node], S_copy[neighbour], node, neighbour));
                }
            }
        }
        Collections.sort(edges_encoding);

        int prev_A = edges_encoding.get(0).A;
        int prev_B = edges_encoding.get(0).B;
        HashSet<Pair<Integer, Integer>> edges_set = new HashSet<Pair<Integer, Integer>>();
        Iterator<FourTuple> iter = edges_encoding.iterator();
        while (!edges_encoding.isEmpty()) {
            FourTuple e_encoding = edges_encoding.pop();
            int A = e_encoding.A;
            int B = e_encoding.B;

            // 移动到新的顶点对，即已经得到前一个顶点对<prev_A, prev_B>的所有边信息，可以开始encode顶点对 <prev_A, prev_B>的超边信息
            if ((A != prev_A || B != prev_B)) { // we've moved onto a different pair of supernodes A and B

                if (prev_A <= prev_B) {
                    double edges_compare_cond = 0;
                    if (prev_A == prev_B) { edges_compare_cond = supernode_sizes[prev_A] * (supernode_sizes[prev_A] - 1) / 4.0; }
                    else                  { edges_compare_cond = (supernode_sizes[prev_A] * supernode_sizes[prev_B]) / 2.0;     }

                    // 不形成超边
                    if (edges_set.size() <= edges_compare_cond) {
                        if (prev_A != prev_B) edges_compressed += edges_set.size();
                        // 每条边加入到C+集合
                        for (Pair<Integer, Integer> edge : edges_set) {
                            Cp_0.add(edge.getValue0());
                            Cp_1.add(edge.getValue1());
                        }
                    }
                    // 行成超边
                    else {
                        if (prev_A != prev_B) edges_compressed += supernode_sizes[prev_A] * supernode_sizes[prev_B] - edges_set.size() + 1;

                        // 加入超边 <prev_A, prev_B>
                        P.add(new Pair(prev_A, prev_B));

                        TIntArrayList in_A = sn_to_n.get(prev_A);
                        TIntArrayList in_B = sn_to_n.get(prev_B);
                        for (int a = 0; a < in_A.size(); a++) {
                            for (int b = 0; b < in_B.size(); b++) {
                                Pair<Integer, Integer> edge = new Pair(in_A.get(a), in_B.get(b));
                                // 每条边加入到C-集合
                                if (!(edges_set.contains(edge))) {
                                    Cm_0.add(in_A.get(a));
                                    Cm_1.add(in_B.get(b));
                                }
                            } // for b
                        } // for a
                    } // else
                } // if

                edges_set = new HashSet<Pair<Integer, Integer>>();
            } // if

            edges_set.add(new Pair(e_encoding.u, e_encoding.v));
            prev_A = A;
            prev_B = B;
        } // for edges encoding
        return (System.currentTimeMillis() - startTime) / 1000.0;
    }

    /**
     * 评价函数，用于评估压缩性能，输出格式为：
     * @Compression: 0.xxxxx
     * @nodes: xxxxx ===> xxxxx
     * @edges: xxxxx ===> xxxxx(P:xxx, C+:xxx, C-:xxx)
     */
    public void evaluatePhase() {
        System.out.println("# Evaluate Phase");
        int sp_num = 0;
        for (int i = 0; i < n; i++) {
            if (I[i] != -1) {
                sp_num++;
            }
        }
        System.out.println(String.format("@Compression: %.5f", (1 - (P.size() + Cp_0.size() + Cm_0.size() * 1.0) / (Gr.numArcs() * 1.0))));
        System.out.println("@nodes: " + Gr.numNodes() + "\t ===> \t" + sp_num);
        System.out.println("@edges: " + Gr.numArcs() + "\t ===> \t" + (P.size() + Cp_0.size() + Cm_0.size()) + String.format("(P:%d, C+:%d, C-:%d)", P.size(), Cp_0.size(), Cm_0.size()));
    }

    /**
     * 用于 Lossy Summarization 的情形，目前没有使用到
     *
     * @param error_bound 对边进行丢弃的阈值参数
     */
    public void dropPhase(double error_bound) {
        System.out.println("# Drop Phase");

        double[] cv = new double[n];
        for (int i = 0; i < n; i++) {
            cv[i] = error_bound * Gr.outdegree(i);
        }

        TIntArrayList updated_Cp_0 = new TIntArrayList();
        TIntArrayList updated_Cp_1 = new TIntArrayList();
        for (int i = 0; i < Cp_0.size(); i++) {
            int edge_u = Cp_0.get(i);
            int edge_v = Cp_1.get(i);

            if (cv[edge_u] >= 1 && cv[edge_v] >= 1) {
                cv[edge_u] = cv[edge_u] - 1;
                cv[edge_v] = cv[edge_v] - 1;
            } else {
                updated_Cp_0.add(edge_u);
                updated_Cp_1.add(edge_v);
            }
        }
        Cp_0 = updated_Cp_0;
        Cp_1 = updated_Cp_1;

        TIntArrayList updated_Cm_0 = new TIntArrayList();
        TIntArrayList updated_Cm_1 = new TIntArrayList();
        for (int i = 0; i < Cm_0.size(); i++) {
            int edge_u = Cm_0.get(i);
            int edge_v = Cm_1.get(i);

            if (cv[edge_u] >= 1 && cv[edge_v] >= 1) {
                cv[edge_u] = cv[edge_u] - 1;
                cv[edge_v] = cv[edge_v] - 1;
            } else {
                updated_Cm_0.add(edge_u);
                updated_Cm_1.add(edge_v);
            }
        }
        Cm_0 = updated_Cm_0;
        Cm_1 = updated_Cm_1;

        Collections.sort(P, new EdgeCompare(supernode_sizes));
        ArrayList<Pair<Integer, Integer>> updated_P = new ArrayList<Pair<Integer, Integer>>();
        for (Pair<Integer, Integer> edge : P) {
            int A = edge.getValue0();
            int B = edge.getValue1();

            if (A == B) {
                updated_P.add(edge);
                continue;
            }

            int size_B = supernode_sizes[B];
            boolean cond_A = true;
            TIntArrayList in_A = sn_to_n.get(A);

            for (int i = 0; i < in_A.size(); i++) {
                if (cv[in_A.get(i)] < size_B) {
                    cond_A = false;
                    break;
                }
            }
            if (!cond_A) {
                updated_P.add(edge);
                continue;
            }

            int size_A = supernode_sizes[A];
            boolean cond_B = true;
            TIntArrayList in_B = sn_to_n.get(B);

            for (int i = 0; i < in_B.size(); i++) {
                if (cv[in_B.get(i)] < size_A) {
                    cond_B = false;
                    break;
                }
            }
            if (!cond_B) {
                updated_P.add(edge);
                continue;
            }

            // if conditions are all true, ie (A != B && all v in A && all v in B)
            for (int i = 0; i < in_A.size(); i++) {
                cv[in_A.get(i)] = cv[in_A.get(i)] - size_B;
            }
            for (int i = 0; i < in_B.size(); i++) {
                cv[in_B.get(i)] = cv[in_B.get(i)] - size_A;
            }

        }
        P = updated_P;
        System.out.println("Drop Compression: " + (1 - (P.size() + Cp_0.size() + Cm_0.size() * 1.0) / (Gr.numArcs() / 2 * 1.0)));
    }

    /**
     * @param iteration              迭代次数
     * @param print_iteration_offset 每执行多少次迭代就进行一次 encode 和 evaluate 进行结果输出
     */
    public void run(int iteration, int print_iteration_offset) {

    }
}
