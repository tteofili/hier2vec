/*
 * Copyright 2017 Tommaso Teofili
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
*/
package com.github.tteofili.p2h;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.commons.collections4.trie.PatriciaTrie;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;
import org.deeplearning4j.models.embeddings.WeightLookupTable;
import org.deeplearning4j.models.word2vec.VocabWord;
import org.deeplearning4j.text.documentiterator.LabelAwareIterator;
import org.deeplearning4j.text.documentiterator.LabelsSource;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

/**
 * Utility class for Par2Hier vector algorithms
 */
class Par2HierUtils {

  private static final String REGEX = "\\.txt\\_(\\d\\.?)+";

  enum Method {
    CLUSTER,
    SUM
  }

  /**
   * perform PCA using SVD
   *
   * @param weightTable the weights to scale
   * @param k the target vector dimensions
   * @return a weight table doc->reduced vector
   */
  static Map<String, INDArray> svdPCA(Map<String, INDArray> weightTable, int k) {
    Map<String, INDArray> reducedVectors = new TreeMap<>();

    INDArray matrix = null;
    int i = 0;
    for (Map.Entry<String, INDArray> entry : weightTable.entrySet()) {
      INDArray vector = entry.getValue();
      if (matrix == null) {
        matrix = Nd4j.zeros(weightTable.size(), vector.columns());
      }
      matrix.putRow(i, vector);
      i++;
    }

    int j = 0;
    INDArray reducedMatrix = Nd4j.create(getTruncatedUT(matrix, k));
    for (Map.Entry<String, INDArray> entry : weightTable.entrySet()) {
      reducedVectors.put(entry.getKey(), reducedMatrix.getRow(j));
      j++;
    }

    return reducedVectors;
  }

  /**
   * transforms paragraph vectors into hierarchical vectors
   * @param iterator iterator over docs
   * @param lookupTable the paragraph vector table
   * @param labels the labels
   * @param k the no. of centroids
   * @return a map doc->hierarchical vector
   */
  static Map<String, INDArray> getPar2Hier(LabelAwareIterator iterator,
                                           WeightLookupTable<VocabWord> lookupTable,
                                           List<String> labels, int k, Method method) {
    Collections.sort(labels);
    LabelsSource labelsSource = iterator.getLabelsSource();
    PatriciaTrie<String> trie = new PatriciaTrie<>();
    for (String label : labels) {
      trie.put(label, label);
    }

    Map<String, INDArray> hvs = new TreeMap<>();
    // for each doc
    for (String node : labelsSource.getLabels()) {
      Par2HierUtils.getPar2HierVector(lookupTable, trie, node, k, hvs, method);
    }
    return hvs;
  }

  /**
   * base case: on a leaf hv = pv
   * on a non-leaf node with n children: hv = pv + k centroids of the n hv
   */
  private static INDArray getPar2HierVector(WeightLookupTable<VocabWord> lookupTable, PatriciaTrie<String> trie, String node,
                                            int k, Map<String, INDArray> hvs, Method method) {
    if (hvs.containsKey(node)) {
      return hvs.get(node);
    }
    INDArray hv = lookupTable.vector(node);
    String[] split = node.split(REGEX);
    Collection<String> descendants = new HashSet<>();
    if (split.length == 2) {
      String separator = ".";
      String prefix = node.substring(0, node.indexOf(split[1])) + separator;

      SortedMap<String, String> sortedMap = trie.prefixMap(prefix);

      for (Map.Entry<String, String> entry : sortedMap.entrySet()) {
        if (prefix.lastIndexOf(separator) == entry.getKey().lastIndexOf(separator)) {
          descendants.add(entry.getValue());
        }
      }
    } else {
      descendants = Collections.emptyList();
    }
    if (descendants.size() == 0) {
      // just the pv
      hvs.put(node, hv);
      return hv;
    } else {
      INDArray chvs = Nd4j.zeros(descendants.size(), hv.columns());
      int i = 0;
      for (String desc : descendants) {
        // child hierarchical vector
        INDArray chv = getPar2HierVector(lookupTable, trie, desc, k, hvs, method);
        chvs.putRow(i, chv);
        i++;
      }

      double[][] centroids;
      if (chvs.rows() > k) {
        centroids = Par2HierUtils.getTruncatedVT(chvs, k);
      } else if (chvs.rows() == 1) {
        centroids = Par2HierUtils.getDoubles(chvs.getRow(0));
      } else {
        centroids = Par2HierUtils.getTruncatedVT(chvs, 1);
      }
      switch (method) {
        case CLUSTER:
          INDArray matrix = Nd4j.zeros(centroids.length + 1, hv.columns());
          matrix.putRow(0, hv);
          for (int c = 0; c < centroids.length; c++) {
            matrix.putRow(c + 1, Nd4j.create(centroids[c]));
          }
          hv = Nd4j.create(Par2HierUtils.getTruncatedVT(matrix, 1));
          break;
        case SUM:
          for (double[] centroid : centroids) {
            hv.addi(Nd4j.create(centroid));
          }
          break;
      }

      hvs.put(node, hv);
      return hv;
    }
  }

  /**
   * truncated SVD as taken from http://stackoverflow.com/questions/19957076/best-way-to-compute-a-truncated-singular-value-decomposition-in-java
   */
  static double[][] getTruncatedSVD(double[][] matrix, final int k) {
    SingularValueDecomposition svd = new SingularValueDecomposition(MatrixUtils.createRealMatrix(matrix));

    double[][] truncatedU = new double[svd.getU().getRowDimension()][k];
    svd.getU().copySubMatrix(0, truncatedU.length - 1, 0, k - 1, truncatedU);

    double[][] truncatedS = new double[k][k];
    svd.getS().copySubMatrix(0, k - 1, 0, k - 1, truncatedS);

    double[][] truncatedVT = new double[k][svd.getVT().getColumnDimension()];
    svd.getVT().copySubMatrix(0, k - 1, 0, truncatedVT[0].length - 1, truncatedVT);

    RealMatrix approximatedSvdMatrix = (MatrixUtils.createRealMatrix(truncatedU)).multiply(
        MatrixUtils.createRealMatrix(truncatedS)).multiply(MatrixUtils.createRealMatrix(truncatedVT));

    return approximatedSvdMatrix.getData();
  }

  static double[][] getTruncatedVT(INDArray matrix, int k) {
    double[][] data = getDoubles(matrix);

    SingularValueDecomposition svd = new SingularValueDecomposition(MatrixUtils.createRealMatrix(data));

    double[][] truncatedVT = new double[k][svd.getVT().getColumnDimension()];
    svd.getVT().copySubMatrix(0, k - 1, 0, truncatedVT[0].length - 1, truncatedVT);
    return truncatedVT;
  }

  static double[][] getTruncatedUT(INDArray matrix, int k) {
    double[][] data = getDoubles(matrix);

    SingularValueDecomposition svd = new SingularValueDecomposition(MatrixUtils.createRealMatrix(data));

    double[][] truncatedU = new double[svd.getU().getRowDimension()][k];
    svd.getU().copySubMatrix(0, truncatedU.length - 1, 0, k - 1, truncatedU);
    return truncatedU;
  }

  private static double[][] getDoubles(INDArray matrix) {
    double[][] data = new double[matrix.rows()][matrix.columns()];
    for (int i = 0; i < data.length; i++) {
      for (int j = 0; j < data[0].length; j++) {
        data[i][j] = matrix.getDouble(i, j);
      }
    }
    return data;
  }

}
