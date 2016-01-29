package hex.grid;

import hex.Model;
import hex.ModelParametersBuilderFactory;
import water.util.Log;

import java.util.*;

public interface HyperSpaceWalker<MP extends Model.Parameters> {

  interface HyperSpaceIterator<MP extends Model.Parameters> {
    /**
     * Get next model parameters.
     *
     * <p>It should return model parameters for next point in hyper space.
     * Throws {@link java.util.NoSuchElementException} if there is no remaining point in space
     * to explore.</p>
     *
     * <p>The method can optimize based on previousModel, but should be
     * able to handle null-value.</p>
     *
     * @param previousModel  model generated for the previous point in hyper space, can be null.
     *
     * @return model parameters for next point in hyper space or null if there is no such point.
     *
     * @throws IllegalArgumentException  when model parameters cannot be constructed
     * @throws java.util.NoSuchElementException if the iteration has no more elements
     */
    MP nextModelParameters(Model previousModel);

    /**
     * Returns true if the iterator can continue.  Takes into account strategy-specific stopping criteria, if any.
     * @param previousModel  optional parameter which helps to determine next step, can be null
     * @return  true if the iterator can produce one more model parameters configuration.
     */
    boolean hasNext(Model previousModel);

    long timeRemaining();

    /**
     * Inform the Iterator that a model build failed in case it needs to adjust its internal state.
     * @param failedModel
     */
    void modelFailed(Model failedModel);

    /**
     * Returns current "raw" state of iterator.
     *
     * The state is represented by a permutation of values of grid parameters.
     *
     * @return  array of "untyped" values representing configuration of grid parameters
     */
    Object[] getCurrentRawParameters();
  }

  /**
   * Returns an iterator to traverse this hyper-space.
   *
   * @return an iterator
   */
  HyperSpaceIterator<MP> iterator();

  /**
   * Returns hyper parameters names which are used for walking the hyper parameters space.
   *
   * The names have to match the names of attributes in model parameters MP.
   *
   * @return names of used hyper parameters
   */
  String[] getHyperParamNames();

  /**
   * Return estimated maximum size of hyperspace, not subject to any early stopping criteria.
   *
   * Can return -1 if estimate is not available.
   *
   * @return size of hyper space to explore
   */
  int getMaxHyperSpaceSize();

  /**
   * Return initial model parameters for search.
   * @return  return model parameters
   */
  MP getParams();

  ModelParametersBuilderFactory<MP> getParametersBuilderFactory();

  /**
   * Superclass for for all hyperparameter space walkers.
   * <p>
   * The external Grid / Hyperparameter search API uses a HashMap<String,Object> to describe a set of hyperparameter
   * values, where the String is a valid field name in the corresponding Model.Parameter, and the Object is
   * the field value (boxed as needed).
   */
  abstract class BaseWalker<MP extends Model.Parameters> implements HyperSpaceWalker<MP> {

    /**
     * Parameters builder factory to create new instance of parameters.
     */
    final transient ModelParametersBuilderFactory<MP> _paramsBuilderFactory;

    /**
     * Used "base" model parameters for this grid search.
     * The object is used as a prototype to create model parameters
     * for each point in hyper space.
     */
    final MP _params;

    /**
     * Hyper space description - in this case only dimension and possible values.
     */
    final protected Map<String, Object[]> _hyperParams;
    /**
     * Cached names of used hyper parameters.
     */
    final protected String[] _hyperParamNames;

    /**
     * Compute max size of hyper space to walk. May include duplicates if points in space are specified multiple
     * times.
     */
    final protected int _maxHyperSpaceSize;

    /**
     *
     * @param paramsBuilderFactory
     * @param hyperParams
     */
    public BaseWalker(MP params,
                           Map<String, Object[]> hyperParams,
                           ModelParametersBuilderFactory<MP> paramsBuilderFactory) {
      _params = params;
      _hyperParams = hyperParams;
      _paramsBuilderFactory = paramsBuilderFactory;
      _hyperParamNames = hyperParams.keySet().toArray(new String[0]);
      _maxHyperSpaceSize = computeMaxSizeOfHyperSpace();
    }

    @Override
    public String[] getHyperParamNames() {
      return _hyperParamNames;
    }

    @Override
    public int getMaxHyperSpaceSize() {
      return _maxHyperSpaceSize;
    }

    @Override
    public MP getParams() {
      return _params;
    }

    @Override
    public ModelParametersBuilderFactory<MP> getParametersBuilderFactory() {
      return _paramsBuilderFactory;
    }

    protected MP getModelParams(MP params, Object[] hyperParams) {
      ModelParametersBuilderFactory.ModelParametersBuilder<MP>
          paramsBuilder = _paramsBuilderFactory.get(params);
      for (int i = 0; i < _hyperParamNames.length; i++) {
        String paramName = _hyperParamNames[i];
        Object paramValue = hyperParams[i];
        paramsBuilder.set(paramName, paramValue);
      }
      return paramsBuilder.build();
    }

    protected int computeMaxSizeOfHyperSpace() {
      int work = 1;
      for (Map.Entry<String, Object[]> p : _hyperParams.entrySet()) {
        if (p.getValue() != null) {
          work *= p.getValue().length;
        }
      }
      return work;
    }

    /** Given a list of indices for the hyperparameter values return an Object[] of the actual values. */
    protected Object[] hypers(int[] hidx, Object[] hypers) {
      for (int i = 0; i < hidx.length; i++) {
        hypers[i] = _hyperParams.get(_hyperParamNames[i])[hidx[i]];
      }
      return hypers;
    }

    protected int integerHash(int[] ar) {
      Integer[] hashMe = new Integer[ar.length];
      for (int i = 0; i < ar.length; i++)
        hashMe[i] = ar[i];
      return Arrays.deepHashCode(hashMe);
    }
  }

  /**
   * Hyperparameter space walker which visits each combination of hyperparameters in order.
   */
  public static class CartesianWalker<MP extends Model.Parameters>
          extends BaseWalker<MP> {

    public CartesianWalker(MP params,
                      Map<String, Object[]> hyperParams,
                      ModelParametersBuilderFactory<MP> paramsBuilderFactory) {
      super(params, hyperParams, paramsBuilderFactory);
    }

    @Override
    public HyperSpaceIterator<MP> iterator() {

      return new HyperSpaceIterator<MP>() {
        /** Hyper params permutation.
         */
        private int[] _currentHyperparamIndices = null;

        @Override
        public MP nextModelParameters(Model previousModel) {
          _currentHyperparamIndices = _currentHyperparamIndices != null ? nextModelIndices(_currentHyperparamIndices) : new int[_hyperParamNames.length];
          if (_currentHyperparamIndices != null) {
            // Fill array of hyper-values
            Object[] hypers = hypers(_currentHyperparamIndices, new Object[_hyperParamNames.length]);
            // Get clone of parameters
            MP commonModelParams = (MP) _params.clone();
            // Fill model parameters
            MP params = getModelParams(commonModelParams, hypers);
            // We have another model parameters
            return params;
          } else {
            throw new NoSuchElementException("No more elements to explore in hyper-space!");
          }
        }

        @Override
        public boolean hasNext(Model previousModel) {
          if (_currentHyperparamIndices == null) {
            return true;
          }
          int[] hyperparamIndices = _currentHyperparamIndices;
          for (int i = 0; i < hyperparamIndices.length; i++) {
            if (hyperparamIndices[i] + 1 < _hyperParams.get(_hyperParamNames[i]).length) {
              return true;
            }
          }
          return false;
        }

        @Override
        public long timeRemaining() { return Long.MAX_VALUE; }

        @Override
        public void modelFailed(Model failedModel) {
          // nada
        }

        @Override
        public Object[] getCurrentRawParameters() {
          Object[] hyperValues = new Object[_hyperParamNames.length];
          return hypers(_currentHyperparamIndices, hyperValues);
        }
      }; // anonymous HyperSpaceIterator class
    } // iterator()

    /**
     * Cartesian iteration over the hyper-parameter space, varying one hyperparameter at a
     * time. Mutates the indices that are passed in and returns them.  Returns NULL when
     * the entire space has been traversed.
     */
    private int[] nextModelIndices(int[] hyperparamIndices) {
      // Find the next parm to flip
      int i;
      for (i = 0; i < hyperparamIndices.length; i++) {
        if (hyperparamIndices[i] + 1 < _hyperParams.get(_hyperParamNames[i]).length) {
          break;
        }
      }
      if (i == hyperparamIndices.length) {
        return null; // All done, report null
      }
      // Flip indices
      for (int j = 0; j < i; j++) {
        hyperparamIndices[j] = 0;
      }
      hyperparamIndices[i]++;
      return hyperparamIndices;
    }
  } // class CartesianWalker

  /**
   * Hyperparameter space walker which visits random combinations of hyperparameters whose possible values are
   * given in explicit lists as they are with CartesianWalker.
   */
  public static class RandomDiscreteValueWalker<MP extends Model.Parameters>
      extends BaseWalker<MP> {
    Random random;

    // stopping criteria:
    private int _max_models;
    private int _max_time_ms;
    public int max_time_ms() { return _max_time_ms; }

    /** All visited hyper params permutations, including the current one. */
    private List<int[]> _visitedPermutations = new ArrayList<>();
    private Set<Integer> _visitedPermutationHashes = new LinkedHashSet<>(); // for fast dupe lookup

    public RandomDiscreteValueWalker(MP params,
                                     Map<String, Object[]> hyperParams,
                                     ModelParametersBuilderFactory<MP> paramsBuilderFactory,
                                     int max_models,
                                     int max_time_ms,
                                     long seed) {
      super(params, hyperParams, paramsBuilderFactory);
      random = new Random(seed); // TODO: allow the user to set the seed
      this._max_models = max_models;
      this._max_time_ms = max_time_ms;
    }

    @Override
    public HyperSpaceIterator<MP> iterator() {
      return new HyperSpaceIterator<MP>() {
        /** Current hyper params permutation. */
        private int[] _currentHyperparamIndices = null;

        /** One-based count of the permutations we've visited, primarily used as an index into _visitedHyperparamIndices. */
        private int _currentPermutationNum = 0;

        /** Start time of this grid */
        private long _start_time = System.currentTimeMillis();

        // TODO: override into a common subclass:
        @Override
        public MP nextModelParameters(Model previousModel) {
          // NOTE: nextModel checks _visitedHyperparamIndices and does not return a duplicate set of indices.
          // NOTE: in RandomDiscreteValueWalker nextModelIndices() returns a new array each time, rather than
          // mutating the last one.
          _currentHyperparamIndices = nextModelIndices();

          if (_currentHyperparamIndices != null) {
            _visitedPermutations.add(_currentHyperparamIndices);
            _visitedPermutationHashes.add(integerHash(_currentHyperparamIndices));
            _currentPermutationNum++; // NOTE: 1-based counting

            // Fill array of hyper-values
            Object[] hypers = hypers(_currentHyperparamIndices, new Object[_hyperParamNames.length]);
            // Get clone of parameters
            MP commonModelParams = (MP) _params.clone();
            // Fill model parameters
            MP params = getModelParams(commonModelParams, hypers);
            // We have another model parameters
            Log.info("About to build model: " + _currentPermutationNum);
            return params;
          } else {
            throw new NoSuchElementException("No more elements to explore in hyper-space!");
          }
        }

        @Override
        public boolean hasNext(Model previousModel) {
          // _currentPermutationNum is 1-based
          return _currentPermutationNum < _maxHyperSpaceSize && _currentPermutationNum < _max_models;
        }

        @Override
        public long timeRemaining() {
          return _max_time_ms - (System.currentTimeMillis() - _start_time);
        }

        @Override
        public void modelFailed(Model failedModel) {
          // Leave _visitedPermutations, _visitedPermutationHashes and _currentHyperparamIndices alone
          // so we don't revisit bad parameters. Note that if a model build fails for other reasons we
          // won't retry.
          _currentPermutationNum--;
        }

        @Override
        public Object[] getCurrentRawParameters() {
          Object[] hyperValues = new Object[_hyperParamNames.length];
          return hypers(_currentHyperparamIndices, hyperValues);
        }
      }; // anonymous HyperSpaceIterator class
    } // iterator()

    /**
     * Random iteration over the hyper-parameter space.  Does not repeat
     * previously-visited combinations.  Returns NULL when we've hit the stopping
     * criteria.
     */
    private int[] nextModelIndices() {
      int[] hyperparamIndices =  new int[_hyperParamNames.length];
      for (int i = 0; i < _hyperParamNames.length; i++) {
        hyperparamIndices[i] = random.nextInt(_hyperParams.get(_hyperParamNames[i]).length);
      }

      // check for aliases and recurse if we've visited this combo before
      if (_visitedPermutationHashes.contains(integerHash(hyperparamIndices)))
        return nextModelIndices();

      return hyperparamIndices;
    } // nextModel

  } // RandomDiscreteValueWalker
}
